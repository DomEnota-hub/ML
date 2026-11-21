#!/usr/bin/env python3
"""Prepare UNQUALIFIED unsigned bytes; never a signed or installable QA release."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile
from gate import ROOT, clean_inputs, exact_sha, sha256, validate_unsigned

clean_inputs()
source=ROOT/'app/build/outputs/apk/release/app-release-unsigned.apk'
validate_unsigned(source)
subprocess.run([sys.executable, str(ROOT/'scripts/ci/dex_invokes.py'), str(source)], cwd=ROOT, check=True)
with zipfile.ZipFile(source) as z:
    if {n.split('/')[1] for n in z.namelist() if n.startswith('lib/')}!={'arm64-v8a'}:raise ValueError('Wrong ABI')
    if 'assets/gapi-builtin-keys.json' in z.namelist():raise ValueError('Protected asset in CI APK')
gradle=(ROOT/'app/build.gradle').read_text()
version=re.search(r"versionName\s+'([^']+)'",gradle).group(1)
code=re.search(r'versionCode\s+(\d+)',gradle).group(1)
package=re.search(r"applicationId\s+'([^']+)'",gradle).group(1)
bt=Path(os.environ['ANDROID_SDK_ROOT'])/'build-tools/35.0.0'
if package!='com.domenota.medialoader':raise ValueError('Unexpected application ID')
if subprocess.run([str(bt/'apksigner'),'verify',str(source)],stdout=subprocess.PIPE,stderr=subprocess.PIPE).returncode==0:raise ValueError('Signed APK cannot enter unsigned handoff')
badging=subprocess.check_output([str(bt/'aapt'),'dump','badging',str(source)],text=True)
if f"package: name='{package}' versionCode='{code}' versionName='{version}'" not in badging:raise ValueError('Package/version mismatch')
dest=ROOT/'arm64-candidate';dest.mkdir()
shutil.copyfile(source,dest/'ML_ARM64_UNSIGNED.apk')
subprocess.run(['git','archive','--format=zip','--output',str(dest/'SOURCES_EXACT_COMMIT.zip'),exact_sha()],cwd=ROOT,check=True)
(dest/'SOURCE_COMMIT_SHA.txt').write_text(exact_sha()+'\n')
with zipfile.ZipFile(dest/'SOURCES_EXACT_COMMIT.zip') as z:
    if z.comment.decode()!=exact_sha() or z.testzip() is not None:raise ValueError('Source archive identity failed')
manifest={'sha':exact_sha(),'package':package,'version':version,'versionCode':int(code),'abi':'arm64-v8a','files':{p.name:sha256(p) for p in dest.iterdir()}}
(dest/'CANDIDATE.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps(manifest,indent=2))
