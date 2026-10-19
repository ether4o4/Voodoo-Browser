#!/usr/bin/env python3
"""Sign an APK without putting signing passwords in command-line arguments."""
import argparse
import json
import os
from pathlib import Path
import subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--keystore',required=True,type=Path)
parser.add_argument('--credentials',required=True,type=Path)
parser.add_argument('--apk',type=Path,default=Path('app/build/outputs/apk/release/app-release-unsigned.apk'))
parser.add_argument('--out',required=True,type=Path)
args=parser.parse_args()
sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:parser.error('Set ANDROID_HOME to the Android SDK directory.')
versions=sorted((p for p in (Path(sdk)/'build-tools').iterdir() if (p/'apksigner').exists()),key=lambda p:tuple(int(s) for s in p.name.split('.') if s.isdigit()))
if not versions:parser.error('Install Android SDK build-tools.')
build_tools=versions[-1]
credentials=json.loads(args.credentials.read_text())
env=os.environ.copy();env['VOODOO_SIGNING_PASSWORD']=credentials['password']
args.out.parent.mkdir(parents=True,exist_ok=True)
aligned=args.out.with_suffix('.aligned.apk')
subprocess.run([str(build_tools/'zipalign'),'-f','-p','4',str(args.apk),str(aligned)],check=True)
subprocess.run([str(build_tools/'apksigner'),'sign','--ks',str(args.keystore),'--ks-key-alias',credentials['alias'],'--ks-pass','env:VOODOO_SIGNING_PASSWORD','--key-pass','env:VOODOO_SIGNING_PASSWORD','--out',str(args.out),str(aligned)],env=env,check=True)
subprocess.run([str(build_tools/'apksigner'),'verify','--verbose',str(args.out)],check=True)
aligned.unlink()
print('Signed APK:',args.out)
