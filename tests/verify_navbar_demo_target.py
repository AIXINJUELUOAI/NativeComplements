"""Verify additional demo reflection against the archived target; never access device."""
from pathlib import Path
import zipfile, hashlib
import verify_signal_target as dex
ROOT=Path(__file__).resolve().parents[1]
checks={
 'android.window.ScreenCaptureInternal$CaptureArgs$Builder': [('setSecureContentPolicy','(I)Landroid/window/ScreenCaptureInternal$CaptureArgs$Builder;'),('setProtectedContentPolicy','(I)Landroid/window/ScreenCaptureInternal$CaptureArgs$Builder;')],
 'android.window.ScreenCaptureInternal$ScreenshotHardwareBuffer': [('containsHdrLayers','()Z'),('containsSecureLayers','()Z'),('asBitmap','()Landroid/graphics/Bitmap;'),('getHardwareBuffer','()Landroid/hardware/HardwareBuffer;')],
 'com.android.server.wm.ActivityRecord': [('inTransition','()Z')],
 'com.android.server.wm.WindowState': [('getOwningUid','()I')],
 'android.app.WindowConfiguration': [('getRotation','()I')],
}
# Include ancestors because some target methods are inherited, not declared locally.
dex.TARGETS=['L'+n.replace('.','/')+';' for n in checks]+['Lcom/android/server/wm/WindowContainer;','Lcom/android/server/wm/WindowToken;']
classes={}
for name,digest in [('framework.jar','89faef564a6b48459c5ffa39ff73523801481524b669493b5b054709cc0e734f'),('services.jar','3ace355c3bfc525cf0145088dc6bdcad37b0b09c1a163ad09a2276aa653f2de9')]:
 p=ROOT/'verification/navbar-system'/name
 assert hashlib.sha256(p.read_bytes()).hexdigest()==digest
 with zipfile.ZipFile(p) as z:
  for n in z.namelist():
   if n.startswith('classes') and n.endswith('.dex'): classes.update(dex.Dex(z.read(n)).classes)
for name, methods in checks.items():
 owner='L'+name.replace('.','/')+';'
 for method,desc in methods:
  candidates=[classes[owner]]
  if name=='com.android.server.wm.ActivityRecord': candidates += [classes[n] for n in ('Lcom/android/server/wm/WindowToken;','Lcom/android/server/wm/WindowContainer;')]
  assert any(m['name']==method and m['descriptor']==desc and m['access_flags'] & 1 for c in candidates for m in c['methods']), (name,method,desc)
print('PASS additional demo capture/security/geometry signatures')
