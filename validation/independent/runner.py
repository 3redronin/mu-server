"""Parse independent-tool evidence conservatively and run the pinned Autobahn image."""
import collections, json, os, subprocess, xml.etree.ElementTree as ET
from pathlib import Path

EXCLUSIONS = {'9.*': 'Large-message/performance cases belong to the separate capacity campaign.',
              '12.*': 'permessage-deflate extension coverage is outside this core RFC6455 profile.',
              '13.*': 'permessage-deflate extension coverage is outside this core RFC6455 profile.'}

def parse_h2spec(path, expected=147):
    root=ET.parse(path).getroot(); rows=[]; seen=set()
    for suite in root.iter('testsuite'):
        for i,t in enumerate(suite.findall('testcase'),1):
            identifier=t.get('package',suite.get('package',''))+'/'+str(i)
            if identifier in seen: raise ValueError('Duplicate h2spec case '+identifier)
            seen.add(identifier)
            bad=t.find('failure') is not None or t.find('error') is not None
            skipped=t.find('skipped') is not None
            rows.append({'id':identifier,'description':t.get('classname',t.get('name','')),
                         'state':'fail' if bad else 'incomplete' if skipped else 'pass',
                         'details':[{'type':c.tag,**c.attrib,'text':c.text} for c in t]})
    if len(rows)!=expected: raise ValueError('Expected %s h2spec cases, received %s'%(expected,len(rows)))
    return rows

def parse_autobahn(directory, agent, expected):
    index=json.loads((directory/'index.json').read_text())
    if set(index)!={agent}: raise ValueError('Unexpected or missing Autobahn agent')
    actual=index[agent]
    if set(actual)!=set(expected): raise ValueError('Autobahn case coverage mismatch: missing=%s unexpected=%s'%(sorted(set(expected)-set(actual)),sorted(set(actual)-set(expected))))
    rows=[]
    for key in expected:
        entry=actual[key]; path=(directory/entry['reportfile']).resolve()
        if path.parent!=directory.resolve(): raise ValueError('Invalid report path')
        result=json.loads(path.read_text())
        if result.get('id')!=key or result.get('agent')!=agent: raise ValueError('Autobahn report identity mismatch')
        for field in ('behavior','behaviorClose'):
            if not result.get(field) or result[field]!=entry.get(field): raise ValueError('Missing/inconsistent Autobahn '+field)
        statuses=[result['behavior'],result['behaviorClose']]
        state='pass' if all(s=='OK' for s in statuses) else 'review' if all(s in ('OK','NON-STRICT','INFORMATIONAL') for s in statuses) else 'fail'
        rows.append({'id':key,'state':state,'behavior':statuses[0],'behaviorClose':statuses[1],
                     'description':result.get('description'),'result':result.get('result'),'resultClose':result.get('resultClose'),
                     'report':entry['reportfile']})
    if not rows: raise ValueError('No Autobahn cases selected')
    return rows

def status(rows):
    states={r['state'] for r in rows}
    return 'incomplete' if not rows or 'incomplete' in states else 'fail' if 'fail' in states else 'review' if 'review' in states else 'pass'

def autobahn_command(tools, directory, args):
    config=json.loads((tools/'image-config.json').read_text())['config']
    command=['bwrap','--ro-bind',str(tools/'rootfs'),'/', '--proc','/proc','--dev','/dev','--tmpfs','/tmp',
             '--bind',str(directory.resolve()),'/reports','--chdir','/reports','--unshare-pid','--die-with-parent','--clearenv']
    for pair in config['Env']:
        key,value=pair.split('=',1);command+=['--setenv',key,value]
    return command+args

def case_inventory(tools,directory,settings):
    code="""import json
from autobahntestsuite.caseset import CaseSet
from autobahntestsuite.case import Cases, CaseCategories, CaseSubCategories, CaseSetname, CaseBasename
s=CaseSet(CaseSetname,CaseBasename,Cases,CaseCategories,CaseSubCategories)
print(json.dumps(s.parseSpecCases(json.load(open('/reports/config.json')))))
"""
    command=autobahn_command(tools,directory,['python','-c',code])
    output=subprocess.check_output(command,text=True,stderr=subprocess.PIPE,timeout=30)
    expected=json.loads(output.strip().splitlines()[-1])
    (directory/'expected-cases.json').write_text(json.dumps(expected,indent=2)+'\n')
    return expected

def summary(output, results, metadata):
    expected={(j,n,t) for j in metadata['jdks'] for n in metadata['servers'] for t in metadata['tools']}
    observed={(r['jdk'],r['server'],r['tool']) for r in results}
    incomplete=expected!=observed or len(observed)!=len(results) or any(r['state']=='incomplete' for r in results)
    candidate=[r for r in results if r['server']==metadata['candidate']]
    passed=bool(candidate) and all(r['state']=='pass' for r in candidate) and not incomplete
    data={'metadata':metadata,'results':results,'candidate_pass':passed,'complete':not incomplete,
          'scope':'Selected independent conformance profile only; not a full release gate.'}
    (output/'results.json').write_text(json.dumps(data,indent=2)+'\n')
    lines=['# Independent conformance results','',data['scope'],'',
           '| JDK | Server | Tool | State | Cases | Pass | Fail | Review |',
           '| --- | --- | --- | --- | ---: | ---: | ---: | ---: |']
    junit=ET.Element('testsuites')
    for r in results:
        counts=collections.Counter(x['state'] for x in r.get('cases',[]))
        lines.append('| %s | %s | %s | %s | %s | %s | %s | %s |'%(r['jdk'],r['server'],r['tool'],r['state'],sum(counts.values()),counts['pass'],counts['fail'],counts['review']))
        suite=ET.SubElement(junit,'testsuite',name='%s-%s-%s'%(r['jdk'],r['server'],r['tool']))
        for c in r.get('cases') or [{'id':'tool-execution','state':r['state'],'reason':r.get('reason')}]:
            case=ET.SubElement(suite,'testcase',name=c['id'],classname=r['tool'])
            if c['state']!='pass': ET.SubElement(case,'failure',message=c['state']).text=json.dumps(c)
        if r.get('reason'):lines+=['',r['server']+' '+r['tool']+': '+r['reason']]
    lines+=['','Candidate passes selected profile: **%s**.'%passed,'',
            'Reference failures are observations, not candidate failures. Missing reference execution still makes the campaign incomplete.',
            'NON-STRICT and INFORMATIONAL results remain visible as review-required, never silently accepted.',
            'See tool subdirectories for original XML/JSON/HTML, commands, fixture identities and logs.','',
            'Exclusions: '+json.dumps(EXCLUSIONS),
            'h2spec 2.6.0 tests RFC7540/7541 (strict mode); this does not certify all RFC9113 behavior.']
    (output/'report.md').write_text('\n'.join(lines)+'\n');ET.indent(junit);ET.ElementTree(junit).write(output/'junit.xml',encoding='utf-8',xml_declaration=True)
    return 2 if incomplete else 0 if passed else 1
