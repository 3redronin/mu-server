#!/usr/bin/env python3
"""Build isolated checkouts and run pinned independent conformance tools over loopback."""
import argparse, hashlib, http.client, json, os, platform, shutil, subprocess, sys, time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from muvalidate import process
from muvalidate.process import Fixture,jdk_path,run_logged,sha256
from runner import EXCLUSIONS,parse_h2spec,parse_autobahn,status,autobahn_command,case_inventory,summary
from prepare import MANIFEST,H2_BINARY


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mu3',type=Path);p.add_argument('--mu4',type=Path)
    p.add_argument('--build',action='store_true',help='Build fixtures first; checkout paths required')
    p.add_argument('--build-dir',type=Path,default=process.ROOT/'target/independent-build')
    p.add_argument('--tools-dir',type=Path,default=process.ROOT/'target/independent-tools')
    p.add_argument('--servers',default='mu3,mu4');p.add_argument('--candidate',default='mu4')
    p.add_argument('--jdks',default='11,17,21,25');p.add_argument('--tools',default='h2spec,autobahn')
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--timeout',type=int,default=1200,help='Maximum seconds per tool/server/JDK')
    p.add_argument('--autobahn-cases',default='*',help='Comma-separated patterns for a focused replay; profile exclusions still apply')
    a=p.parse_args();names=a.servers.split(',');versions=list(map(int,a.jdks.split(',')));selected=a.tools.split(',')
    if a.candidate not in names or set(names)-{'mu3','mu4'} or set(versions)-{11,17,21,25} or set(selected)-{'h2spec','autobahn'}:p.error('Invalid server, candidate, JDK or tool')
    if any(len(xs)!=len(set(xs)) for xs in (names,versions,selected)):p.error('Duplicate matrix entries')
    if a.timeout<=0:p.error('Timeout must be positive')
    output=a.output.resolve();output.mkdir(parents=True,exist_ok=False)
    tools=a.tools_dir.resolve();process.TARGET=a.build_dir.resolve()
    metadata={'candidate':a.candidate,'servers':names,'jdks':versions,'tools':selected,'command':sys.argv,
              'started':time.strftime('%Y-%m-%dT%H:%M:%S%z'),'platform':platform.platform(),
              'autobahn_patterns':a.autobahn_cases.split(','),'exclusions':EXCLUSIONS,'fixture_profile':'independent',
              'limits':{'websocket_frame_bytes':16777216,'websocket_message_bytes_mu4':16777216,'websocket_idle_seconds':60},
              'harness_sources':{str(x.relative_to(process.ROOT)):sha256(x) for folder in ('independent','muvalidate','fixture') for x in (process.ROOT/folder).rglob('*') if x.suffix in ('.py','.java')}}
    metadata['runtime_versions']={str(v):subprocess.check_output([str(jdk_path(v)/'bin/java'),'-version'],stderr=subprocess.STDOUT,text=True) for v in versions}
    for relative in metadata['harness_sources']:
        snapshot=output/'harness'/relative;snapshot.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(process.ROOT/relative,snapshot)
    results=[]
    if 'autobahn' in selected:
        runtime=tools/'runtime-files.json'
        if runtime.exists():
            for relative,expected in json.loads(runtime.read_text()).items():
                path=tools/'rootfs'/relative
                if 'link' in expected:
                    if not path.is_symlink() or os.readlink(path)!=expected['link']:raise RuntimeError('Changed tool symlink: '+relative)
                elif not path.is_file() or sha256(path)!=expected['sha256']:raise RuntimeError('Changed tool file: '+relative)
            metadata['autobahn_runtime_inventory_sha256']=sha256(runtime)
        else: raise RuntimeError('Missing tool runtime inventory: rerun independent/prepare.py')
    if a.build:
        for name in names:
            checkout=getattr(a,name)
            if checkout is None:p.error('--build requires --'+name)
            process.build(name,checkout.resolve(),jdk_path(21))
    for version in versions:
        for name in names:
            for tool in selected:
                d=output/str(version)/name/tool;d.mkdir(parents=True)
                result={'server':name,'jdk':version,'tool':tool,'state':'incomplete','cases':[]}
                print('Running',version,name,tool,flush=True)
                try:
                    if tool=='h2spec':
                        if sha256(tools/'h2spec')!=H2_BINARY:raise RuntimeError('Pinned h2spec binary missing or changed; run prepare.py')
                    else:
                        installed=json.loads((tools/'installed.json').read_text())
                        if installed['autobahn']['image'].split('@')[1]!=MANIFEST:raise RuntimeError('Autobahn image identity mismatch')
                        if sha256(tools/'image-manifest.json')!=MANIFEST.split(':')[1]:raise RuntimeError('Autobahn manifest changed')
                        image=json.loads((tools/'image-manifest.json').read_text())
                        if sha256(tools/'image-config.json')!=image['config']['digest'].split(':')[1]:raise RuntimeError('Autobahn runtime configuration changed')
                    with Fixture(name,jdk_path(version),d,configuration='independent') as f:
                        result['source']=f.metadata['source']
                        if tool=='h2spec':
                            cmd=[str(tools/'h2spec'),'-h','127.0.0.1','-p',str(f.https),'-t','-k','-S','--max-header-length','8192','-j',str(d/'h2spec.xml')]
                            result['tool_identity']={'version':'2.6.0','sha256':H2_BINARY}
                        else:
                            settings={'outdir':'/reports','servers':[{'agent':name,'url':'ws://127.0.0.1:%s/ws'%f.http}],
                                      'cases':a.autobahn_cases.split(','),'exclude-cases':list(EXCLUSIONS),'exclude-agent-cases':{}}
                            (d/'config.json').write_text(json.dumps(settings,indent=2)+'\n')
                            expected=case_inventory(tools,d,settings)
                            cmd=autobahn_command(tools,d,['wstest','-m','fuzzingclient','-s','/reports/config.json'])
                            result['tool_identity']=installed['autobahn'];result['expected_cases']=expected
                        result['command']=cmd;(d/'command.json').write_text(json.dumps(cmd,indent=2)+'\n')
                        failure=None
                        try:run_logged(cmd,d/'tool.log',timeout=a.timeout)
                        except (RuntimeError,subprocess.TimeoutExpired) as error:failure=str(error)
                        result['cases']=parse_h2spec(d/'h2spec.xml') if tool=='h2spec' else parse_autobahn(d,name,expected)
                        result['state']=status(result['cases'])
                        if failure:
                            result['tool_failure']=failure
                            if result['state']=='pass':result['state']='incomplete'
                        # A fresh ordinary request demonstrates usability after the tool run.
                        connection=http.client.HTTPConnection('127.0.0.1',f.http,timeout=5)
                        try:
                            connection.request('GET','/hello'); response=connection.getresponse()
                            if response.status!=200 or response.read()!=b'hello':raise RuntimeError('Post-tool health check failed')
                        finally:connection.close()
                    if 'STOPPED true' not in (d/'server.log').read_text():raise RuntimeError('Fixture did not stop cleanly')
                except Exception as error:
                    result['state']='incomplete';result['reason']=str(error)
                    (d/'exception.txt').write_text(repr(error)+'\n')
                results.append(result);summary(output,results,metadata)
                print('Result:',result['state'],len(result['cases']),'cases',result.get('reason',''),flush=True)
    metadata['finished']=time.strftime('%Y-%m-%dT%H:%M:%S%z')
    exit_code=summary(output,results,metadata)
    print('Report:',output/'report.md',flush=True)
    return exit_code
if __name__=='__main__':sys.exit(main())
