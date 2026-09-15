#!/usr/bin/env python3
"""Install pinned Linux/amd64 conformance tools without a Docker daemon."""
import argparse, hashlib, io, json, os, platform, shutil, subprocess, tarfile, urllib.request
from pathlib import Path

IMAGE = 'crossbario/autobahn-testsuite'
MANIFEST = 'sha256:519915fb568b04c9383f70a1c405ae3ff44ab9e35835b085239c258b6fac3074'
H2_ARCHIVE = '157ee0de702e01ad40e752dbf074b366027e550c8e7504f9450da2809e279318'
H2_BINARY = 'ac679b916bcd46c52314b17c8903d8dffebf0f2357586d272731f6d8bfd5e9f7'

def digest(path):
    with Path(path).open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()

def download(url, destination, expected, headers=None):
    if destination.exists() and digest(destination) == expected: return
    temp = destination.with_suffix('.download')
    if temp.exists() and digest(temp)==expected:
        temp.replace(destination); return
    for attempt in range(5):
        offset=temp.stat().st_size if temp.exists() else 0
        request_headers=dict(headers or {})
        if offset: request_headers['Range']='bytes=%d-'%offset
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=request_headers), timeout=30) as src:
                append=offset and src.status==206 and src.headers.get('Content-Range','').startswith('bytes %d-'%offset)
                with temp.open('ab' if append else 'wb') as dst: shutil.copyfileobj(src,dst)
            if digest(temp)!=expected:
                temp.unlink(); raise RuntimeError('Checksum mismatch: '+url)
            break
        except (TimeoutError, OSError) as error:
            if attempt==4: raise
            print('Retrying download after:',error,flush=True)

    temp.replace(destination)

def seal_runtime(dest):
    root=dest/'rootfs'
    files={}
    for path in sorted((root/'opt/pypy').rglob('*')):
        if path.is_symlink(): files[str(path.relative_to(root))]={'link':os.readlink(path)}
        elif path.is_file(): files[str(path.relative_to(root))]={'sha256':digest(path)}
    if not files: raise RuntimeError('Missing Autobahn runtime')
    (dest/'runtime-files.json').write_text(json.dumps(files,indent=2)+'\n')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--tools-dir', type=Path, default=Path(__file__).resolve().parents[1]/'target/independent-tools')
    a=p.parse_args()
    if platform.system()!='Linux' or platform.machine() not in ('x86_64','amd64'): p.error('This pinned toolchain requires Linux amd64')
    dest=a.tools_dir.resolve();dest.mkdir(parents=True,exist_ok=True)
    archive=dest/'h2spec.tar.gz'
    download('https://github.com/summerwind/h2spec/releases/download/v2.6.0/h2spec_linux_amd64.tar.gz',archive,H2_ARCHIVE)
    with tarfile.open(archive) as t:
        member=next(m for m in t.getmembers() if m.name in ('h2spec','./h2spec') and m.isfile())
        (dest/'h2spec').write_bytes(t.extractfile(member).read())
    assert digest(dest/'h2spec')==H2_BINARY
    (dest/'h2spec').chmod(0o755)
    if (dest/'installed.json').exists():
        if not (dest/'runtime-files.json').exists(): seal_runtime(dest)
        print('Tools already installed:',dest);return
    if not shutil.which('bwrap'): raise RuntimeError('bubblewrap (bwrap) is required for the daemon-free Autobahn runner')
    token=json.load(urllib.request.urlopen('https://auth.docker.io/token?service=registry.docker.io&scope=repository:'+IMAGE+':pull',timeout=30))['token']
    headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.docker.distribution.manifest.v2+json'}
    base='https://registry-1.docker.io/v2/'+IMAGE
    manifest=dest/'image-manifest.json';download(base+'/manifests/'+MANIFEST,manifest,MANIFEST.split(':')[1],headers)
    m=json.loads(manifest.read_text()); layers=dest/'layers';layers.mkdir(exist_ok=True)
    config=dest/'image-config.json';download(base+'/blobs/'+m['config']['digest'],config,m['config']['digest'].split(':')[1],headers)
    for index,layer in enumerate(m['layers']):
        print('Downloading verified layer',index+1,'of',len(m['layers']),flush=True)
        download(base+'/blobs/'+layer['digest'],layers/(layer['digest'].split(':')[1]+'.tar.gz'),layer['digest'].split(':')[1],headers)
    root=dest/'rootfs'
    if root.exists(): raise RuntimeError('Partial rootfs exists; remove only this installation rootfs and retry: '+str(root))
    root.mkdir()
    for layer in m['layers']:
        archive=layers/(layer['digest'].split(':')[1]+'.tar.gz')
        # Extraction can write only inside rootfs. The host filesystem is read-only,
        # including targets of any absolute symlinks carried by the trusted image.
        with tarfile.open(archive) as t:
            whiteouts=[m.name for m in t.getmembers() if Path(m.name).name.startswith('.wh.')]
        for name in whiteouts:
            path=Path(name); parent=(root/path.parent).resolve()
            if not parent.is_relative_to(root): raise RuntimeError('Whiteout escapes rootfs')
            targets=list(parent.iterdir()) if path.name=='.wh..wh..opq' and parent.exists() else [parent/path.name[4:]]
            for target in targets:
                if target.is_symlink() or target.is_file(): target.unlink()
                elif target.is_dir(): shutil.rmtree(target)
        subprocess.run(['bwrap','--ro-bind','/','/','--bind',str(root),str(root),'--unshare-pid','--die-with-parent',
                        'tar','--no-same-owner','--no-same-permissions','--exclude=dev/*','--exclude=./dev/*','--exclude=*/.wh.*',
                        '-xzf',str(archive),'-C',str(root)],check=True)
    for mountpoint in ('reports','config'): (root/mountpoint).mkdir(exist_ok=True)
    installed={'h2spec':{'version':'2.6.0','sha256':H2_BINARY},'autobahn':{'version':'25.10.1','image':IMAGE+'@'+MANIFEST,'manifest_sha256':MANIFEST.split(':')[1],'config_digest':m['config']['digest']}}
    seal_runtime(dest)
    (dest/'installed.json').write_text(json.dumps(installed,indent=2)+'\n')
    print('Installed:',dest)
if __name__=='__main__': main()
