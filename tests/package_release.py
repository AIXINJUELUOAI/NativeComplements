"""Verify the built artifact, preserve provenance, and assemble the local delivery."""
from pathlib import Path
import hashlib,json,os,shutil,struct,subprocess,zipfile,xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
apk=root/'app/build/outputs/apk/release/app-release.apk'
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',str(Path(os.environ['LOCALAPPDATA'])/'Android/Sdk')))
tools=sdk/'build-tools/36.1.0'
jdk=Path(os.environ.get('JAVA_HOME',r'C:\Program Files\Android\Android Studio\jbr'))
out=root/'deliverables';out.mkdir(exist_ok=True)
evidence=root/'verification';evidence.mkdir(exist_ok=True)

def run(*args):
    result=subprocess.run(list(map(str,args)),capture_output=True,text=True,encoding='utf-8',errors='replace')
    assert result.returncode==0,result.stdout+result.stderr
    return result.stdout+result.stderr

def dex_classes(data):
    def u32(offset):return struct.unpack_from('<I',data,offset)[0]
    string_count,string_offset=u32(0x38),u32(0x3c)
    types_offset=u32(0x44)
    def string(index):
        offset=u32(string_offset+4*index)
        while data[offset]&128:offset+=1
        offset+=1
        return data[offset:data.index(b'\0',offset)].decode('utf-8',errors='replace')
    result=set()
    for index in range(u32(0x60)):
        cls=u32(u32(0x64)+32*index)
        result.add(string(u32(types_offset+4*cls)))
    return result

with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None
    classes=set()
    for name in archive.namelist():
        if name.endswith('.dex'):classes.update(dex_classes(archive.read(name)))
    expected=json.loads((root/'baseline/runtime-sha256.json').read_text())
    for name in [*expected,'MainActivity.java','SettingsActivity.java']:
        assert 'Ldev/local/sunwindowdemo/'+name.removesuffix('.java')+';' in classes,name
    assert not any(x.startswith('Lio/github/libxposed/api/') for x in classes),'compileOnly API bundled'
    scopes=archive.read('META-INF/xposed/scope.list').decode().splitlines()
    assert set(scopes)=={'system','com.google.android.apps.nexuslauncher','com.android.systemui'}
    entries=archive.read('META-INF/xposed/java_init.list').decode().splitlines()
    assert set(entries)=={'dev.local.sunwindowdemo.SunWindowModule','dev.local.sunwindowdemo.LauncherGuard','dev.local.sunwindowdemo.BackBridge'}
    assert archive.read('META-INF/LICENSE')
    assert archive.read('META-INF/NOTICE')
    assert not any(n.endswith(('.keystore','.jks','.jar')) for n in archive.namelist())

signature=run(jdk/'bin/java.exe','-jar',tools/'lib/apksigner.jar','verify','--verbose','--print-certs',apk)
expected_cert='28bbc4a8959e73a9282f94d33b6d51c6f730d4973c4a37c290d70f8274583104'
assert expected_cert in signature
badging=run(tools/'aapt2.exe','dump','badging',apk)
assert "name='dev.local.sunwindowdemo'" in badging and "versionCode='13'" in badging
assert "targetSdkVersion:'37'" in badging and ("minSdkVersion:'34'" in badging or "sdkVersion:'34'" in badging)
assert 'android.permission.INTERNET' not in badging
(evidence/'apk-signature.txt').write_text(signature,encoding='utf-8')
(evidence/'apk-badging.txt').write_text(badging,encoding='utf-8')
results=list((root/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
assert results, 'Missing management UI test results'
test_count=0
for result in results:
    suite=ET.parse(result).getroot()
    assert int(suite.attrib.get('failures',0))==0 and int(suite.attrib.get('errors',0))==0, result.name
    test_count+=int(suite.attrib['tests'])
    (evidence/'junit').mkdir(exist_ok=True)
    shutil.copy2(result,evidence/'junit'/result.name)
assert test_count>=5, 'Incomplete UI/config/navigation verification'
assert 'PASS: original seven host checks' in (evidence/'runtime-checks.txt').read_text(encoding='utf-8-sig')
summary=f'''# 第一版验证记录

交付版本：0.1.0-preview1 / versionCode 13 / 协议 14。

## 本轮完成

- `python tests/verify_runtime.py`：26 个运行层 Java 文件与原始 Demo8.2 源码 ZIP 逐字节一致；原有七组 JVM 规则检查全部通过。
- `gradlew.bat --no-daemon --console=plain :app:assembleRelease :app:testDebugUnitTest`：构建、release lint 和 {test_count} 项管理界面/配置/导航检查通过。
- `python tests/package_release.py`：APK CRC、DEX 定义、三个作用域、三个 Hook 入口、包名、版本号、minSdk34/targetSdk37、许可证、compileOnly API 未打包及无私钥检查通过。
- apksigner 校验通过，证书 SHA256 为 `{expected_cert}`，与 Demo8.2 一致。
- 保留原偏好文件、键、默认值与应用顺序；未引入全局配置迁移或改变通信协议。

## 界面检查

`verification/screenshots` 保存 Robolectric API35 离屏渲染，包括首页、小窗设置和深色手势页。该渲染用于检查排版与控件，不是目标 PixelOS 手机截图。真实 Activity 导航测试覆盖首页进入小窗、进入手势页、逐层返回，以及首页状态入口返回原页面。

## 保留的适配例外

Release lint 对旧 LauncherGuard 的 InputEvent.getDisplayId 反射报告 BlockedPrivateApi。app/lint.xml 仅对该原有文件豁免该项；运行代码、目标声明预检和其他 lint 检查保留。它在目标桌面 Hook 进程运行，不宣称普通应用或其他 ROM 也可直接使用。

## 验证边界

本轮没有安装、启用、重启或操作手机。源码一致和主机规则通过，证明迁移未修改原有窗口算法与 Hook 源码，不等于对 ART Hook 时序、真实触摸、应用兼容、动画帧率、系统恢复已完成新版本真机验收。单活动窗口、180 秒期限和旧版已知问题保留。

## 现场验收顺序

1. 旧版先恢复窗口，再覆盖安装并重启；检查三个作用域、原设置和无障碍连接。
2. 左右角落菜单、五个快捷应用排序、固定列表入口、侧边返回长滑。
3. 普通/迷你/全屏、白条缩放、拖动关闭和空白处关闭方式。
4. 横屏、截图工具栏、应用切换、历史任务隐藏及恢复入口。

原始 APK 与源码保存在 baseline；低版本 APK 不作为已验证可覆盖回退的安装包。停用与回退说明见 README。
'''
(root/'VERIFICATION.md').write_text(summary,encoding='utf-8')
target=out/'NativeComplements-0.1.0-preview1.apk';shutil.copy2(apk,target)
sources=out/'NativeComplements-0.1.0-preview1-source.zip'
excluded={'.git','.gradle','.kotlin','.local','build','deliverables','host-classes','__pycache__'}
with zipfile.ZipFile(sources,'w',zipfile.ZIP_DEFLATED) as archive:
    for path in root.rglob('*'):
        rel=path.relative_to(root)
        if not path.is_file() or set(rel.parts)&excluded or path.suffix in ('.log','.keystore','.jks') or path.name=='local.properties':continue
        archive.write(path,rel.as_posix())
with zipfile.ZipFile(sources) as archive:assert archive.testzip() is None
report={'apk':target.name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'bytes':target.stat().st_size,
        'source_zip':sources.name,'source_sha256':hashlib.sha256(sources.read_bytes()).hexdigest(),
        'certificate_sha256':expected_cert,'runtime_files_unchanged':len(expected),'protocol':14,'versionCode':13,
        'scopes':scopes,'entries':entries,'management_tests_passed':test_count,'device_installation_performed':False}
(out/'CHECKSUMS.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False,indent=2))
