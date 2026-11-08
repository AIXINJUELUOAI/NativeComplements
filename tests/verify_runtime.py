"""Check preserved runtime sources and run the original seven JVM checks."""
import hashlib, json, os, pathlib, subprocess, zipfile
root=pathlib.Path(__file__).resolve().parents[1]
src=root/'app/src/main/java/dev/local/sunwindowdemo/features/floatingwindow/legacy'
manifest=json.loads((root/'baseline/runtime-sha256.json').read_text())
for name, expected in manifest.items():
    assert hashlib.sha256((src/name).read_bytes()).hexdigest()==expected, name+' changed'
with zipfile.ZipFile(root/'baseline/sunwindow-demo8.2-source.zip') as original:
    for name in manifest:
        candidates=[n for n in original.namelist() if n.endswith('/'+name)]
        assert len(candidates)==1, name
        assert original.read(candidates[0])==(src/name).read_bytes(), name
print('PASS: 26 runtime Java sources exactly match the packaged Demo8.2 baseline')
jdk=pathlib.Path(os.environ.get('JAVA_HOME',r'C:\Program Files\Android\Android Studio\jbr'))/'bin'
out=root/'verification/host-classes';out.mkdir(parents=True,exist_ok=True)
names=['Geometry','GestureMath','Rules','GestureGate','MotionStyle','BackSwipe','WindowHistory']
files=[src/(n+'.java') for n in names]+list((root/'tests/host').glob('*.java'))
subprocess.run([str(jdk/'javac.exe'),'--release','17','-encoding','UTF-8','-d',str(out),*map(str,files)],check=True)
for n in ['GeometryCheck','StateChecks','GestureGateChecks','GeometryChecks','MotionChecks','DropChecks','HistoryChecks']:
    subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'dev.local.sunwindowdemo.'+n],check=True)
print('PASS: original seven host checks')
