"""Check immutable provenance, documented runtime changes, and JVM regression checks."""
import hashlib, json, os, pathlib, shutil, subprocess, zipfile
root=pathlib.Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy'
manifest=json.loads((root/'baseline/runtime-sha256.json').read_text())
changes=json.loads((root/'tests/runtime-changes.json').read_text())
actual=set()
with zipfile.ZipFile(root/'baseline/sunwindow-demo8.2-source.zip') as original:
    for name,expected in manifest.items():
        candidates=[n for n in original.namelist() if n.endswith('/'+name)]
        assert len(candidates)==1,name
        assert hashlib.sha256(original.read(candidates[0])).hexdigest()==expected,'Baseline was modified: '+name
        path=src/name
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=expected:actual.add(name)
added={p.name for p in src.glob('*.java')}-set(manifest)
actual.update(added)
assert actual==set(changes),(actual-set(changes),set(changes)-actual)
baseline_changes=actual & set(manifest)
print(f'PASS: immutable Demo8.2 baseline; {len(manifest)-len(baseline_changes)} unchanged, {len(baseline_changes)} documented changes/removals, {len(added)} documented additions')
def find_tool(name):
    home=os.environ.get('JAVA_HOME')
    if home:
        for candidate in (pathlib.Path(home)/'bin'/(name+'.exe'), pathlib.Path(home)/'bin'/name):
            if candidate.is_file():return str(candidate)
    found=shutil.which(name)
    if not found:raise SystemExit('missing JDK tool: '+name)
    return found
JAVAC=find_tool('javac');JAVA=find_tool('java')
out=root/'verification/host-classes';out.mkdir(parents=True,exist_ok=True)
names=['Geometry','GestureMath','Rules','GestureGate','MotionStyle','BackSwipe','WindowHistory','WindowPolicy','RotationGate','MiniMagnet','CornerGesture',
       'WindowAdmission','WindowPresentation','WindowControl','LongBackGesture','OpenWaitState','LaunchCoverFlow',
       'WindowNavigation','BackHandoffState','WindowHostPolicy','WindowRecoveryPolicy','WindowLifecycle']
files=[root/'app/src/main/java/dev/local/sunwindowdemo/features/motion/MotionPolicy.java']+[src/(n+'.java') for n in names]+[root/'app/src/main/java/dev/local/sunwindowdemo/features/edgetouch/EdgeTouchGeometry.java']+list((root/'tests/host').glob('*.java'))
subprocess.run([JAVAC,'--release','17','-encoding','UTF-8','-d',str(out),*map(str,files)],check=True)
for n in ['GeometryCheck','StateChecks','GestureGateChecks','GeometryChecks','MotionChecks','DropChecks','HistoryChecks']:
    subprocess.run([JAVA,'-cp',str(out),'dev.local.sunwindowdemo.'+n],check=True)
print('PASS: original seven host checks')
subprocess.run([JAVA,'-cp',str(out),'dev.local.sunwindowdemo.WindowPolicyChecks'],check=True)
subprocess.run([JAVA,'-cp',str(out),'dev.local.sunwindowdemo.RotationChecks'],check=True)
subprocess.run([JAVA,'-cp',str(out),'dev.local.sunwindowdemo.EdgeTouchChecks'],check=True)

subprocess.run([JAVA,'-cp',str(out),'dev.local.sunwindowdemo.MiniMagnetChecks'],check=True)

# Keep surface-mask checks isolated from Android-free geometry classes.
visibility_out=root/'verification/visibility-classes';visibility_out.mkdir(parents=True,exist_ok=True)
visibility_files=[src/'TaskVisibilityGuard.java',*(root/'tests/host/visibility').rglob('*.java')]
subprocess.run([JAVAC,'--release','17','-encoding','UTF-8','-d',str(visibility_out),*map(str,visibility_files)],check=True)
subprocess.run([JAVA,'-cp',str(visibility_out),'dev.local.sunwindowdemo.TaskVisibilityGuardChecks'],check=True)

subprocess.run([JAVA,'-cp',str(out),'dev.local.sunwindowdemo.CornerGestureChecks'],check=True)

subprocess.run([JAVA,'-cp',str(out),'dev.local.sunwindowdemo.MotionPolicyChecks'],check=True)
