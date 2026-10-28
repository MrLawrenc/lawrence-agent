#!/usr/bin/env python3
"""Fetch a public Docker Hub image on the host, verify digests, and create a docker-load archive.

Useful when a Colima VM cannot resolve the registry. Never changes daemon DNS or Docker credentials.
"""
import argparse
import gzip
import hashlib
import io
import json
import pathlib
import tarfile
import urllib.request


def sha256(path):
    digest=hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda:source.read(1024*1024),b''):digest.update(chunk)
    return digest.hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image',default='rabbitmq:4.1-alpine')
    parser.add_argument('--arch',choices=['arm64','amd64'],default='arm64')
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    name,tag=args.image.rsplit(':',1)
    if '/' not in name:name='library/'+name
    if not name.startswith('library/') or not all(c.isalnum() or c in '/_-.:' for c in name+tag):
        raise ValueError('This helper only downloads public official Docker Hub images')
    output=pathlib.Path(args.output).resolve();output.parent.mkdir(parents=True,exist_ok=True)
    cache=output.parent/(output.stem+'-layers');cache.mkdir(exist_ok=True)
    auth='https://auth.docker.io/token?service=registry.docker.io&scope=repository:'+name+':pull'
    with urllib.request.urlopen(auth,timeout=30) as response:token=json.load(response)['token']
    accept=', '.join(['application/vnd.oci.image.index.v1+json','application/vnd.docker.distribution.manifest.list.v2+json','application/vnd.oci.image.manifest.v1+json','application/vnd.docker.distribution.manifest.v2+json'])
    def open_registry(path):
        request=urllib.request.Request('https://registry-1.docker.io/v2/'+name+'/'+path,
                                       headers={'Authorization':'Bearer '+token,'Accept':accept})
        return urllib.request.urlopen(request,timeout=60)
    def document(reference):
        with open_registry('manifests/'+reference) as response:body=response.read(8*1024*1024)
        digest='sha256:'+hashlib.sha256(body).hexdigest()
        if reference.startswith('sha256:') and reference!=digest:raise ValueError('Manifest digest mismatch')
        return json.loads(body),digest
    manifest,digest=document(tag)
    if 'manifests' in manifest:
        candidates=[m for m in manifest['manifests'] if m.get('platform',{}).get('os')=='linux' and m.get('platform',{}).get('architecture')==args.arch]
        if not candidates:raise ValueError('No matching Linux platform')
        manifest,digest=document(candidates[0]['digest'])
    def blob(description):
        expected=description['digest'];path=cache/expected.split(':')[1]
        if path.is_file() and sha256(path)==expected.split(':')[1]:return path
        pending=path.with_suffix('.part');hasher=hashlib.sha256();size=0
        try:
            with open_registry('blobs/'+expected) as response,pending.open('wb') as target:
                while True:
                    chunk=response.read(1024*1024)
                    if not chunk:break
                    size+=len(chunk)
                    if size>512*1024*1024:raise ValueError('Compressed layer exceeds 512MiB')
                    hasher.update(chunk);target.write(chunk)
            if 'sha256:'+hasher.hexdigest()!=expected:raise ValueError('Layer digest mismatch')
            pending.replace(path);return path
        finally:pending.unlink(missing_ok=True)
    config_path=blob(manifest['config']);config=json.loads(config_path.read_bytes())
    config_name=manifest['config']['digest'].split(':')[1]+'.json';layer_names=[]
    with tarfile.open(output,'w') as archive:
        archive.add(config_path,arcname=config_name)
        for index,description in enumerate(manifest['layers']):
            print('Downloading/verifying layer',index+1,'/',len(manifest['layers']),flush=True)
            compressed=blob(description);unpacked=compressed.with_suffix('.tar');hasher=hashlib.sha256();size=0
            with compressed.open('rb') as source:
                magic=source.read(2);source.seek(0)
                stream=gzip.GzipFile(fileobj=source) if magic==b'\x1f\x8b' else source
                with unpacked.open('wb') as target:
                    while True:
                        chunk=stream.read(1024*1024)
                        if not chunk:break
                        size+=len(chunk)
                        if size>2*1024*1024*1024:raise ValueError('Layer exceeds 2GiB decompressed')
                        hasher.update(chunk);target.write(chunk)
            if 'sha256:'+hasher.hexdigest()!=config['rootfs']['diff_ids'][index]:raise ValueError('Uncompressed layer digest mismatch')
            layer_name=description['digest'].split(':')[1]+'/layer.tar';layer_names.append(layer_name);archive.add(unpacked,arcname=layer_name);unpacked.unlink()
        data=json.dumps([{'Config':config_name,'RepoTags':[args.image],'Layers':layer_names}]).encode()
        info=tarfile.TarInfo('manifest.json');info.size=len(data);archive.addfile(info,io.BytesIO(data))
    metadata={'image':args.image,'platform':'linux/'+args.arch,'manifestDigest':digest,'archive':str(output),'sha256':sha256(output)}
    output.with_suffix('.json').write_text(json.dumps(metadata,indent=2));print(json.dumps(metadata),flush=True)


if __name__=='__main__':main()
