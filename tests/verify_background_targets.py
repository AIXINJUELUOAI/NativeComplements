"""Verify background-management members in the two local Android 17 host archives."""

from pathlib import Path
import sys

import verify_test4_target as dex
from verify_pixel_alive_targets import instructions, read_with_code


AM = 'Lcom/android/server/am/'
WM = 'Lcom/android/server/wm/'
PROC = AM + 'ProcessRecord;'
STATE = AM + 'psc/ProcessRecordInternal;'
APP = 'Landroid/content/pm/ApplicationInfo;'
THREAD = 'Landroid/app/IApplicationThread;'
STRING = 'Ljava/lang/String;'
CONTEXT = 'Landroid/content/Context;'
WP = WM + 'WindowProcessController;'
TRACKER = WM + 'VisibleActivityProcessTracker;'
LOC = 'Lcom/android/server/location/provider/LocationProviderManager'
IDENTITY = 'Landroid/location/util/identity/CallerIdentity;'
AUDIO = 'Landroid/media/AudioPlaybackConfiguration;'
FREEZER = AM + 'Freezer;'
OPTIMIZER = AM + 'CachedAppOptimizer;'
CACHED_RECORD = AM + 'ProcessCachedOptimizerRecord;'
FREEZE_HANDLER = AM + 'CachedAppOptimizer$FreezeHandler;'
ANDROID_PROCESS = 'Landroid/os/Process;'
SERVICE_MANAGER = 'Landroid/os/ServiceManager;'
BR = AM + 'BroadcastRecord;'
INTENT = 'Landroid/content/Intent;'
PROVIDER = AM + 'ContentProviderHelper;'
SERVICES = AM + 'ActiveServices;'
LOOKUP = AM + 'ActiveServices$ServiceLookupResult;'
SERVICE = AM + 'ServiceRecord;'
START_REQUEST = WM + 'ActivityStarter$Request;'
IBINDER = 'Landroid/os/IBinder;'
PROVIDER_HOLDER = 'Landroid/app/ContentProviderHolder;'
POWER = 'Lcom/android/server/power/PowerManagerService;'
WAKE_LOCK = 'Lcom/android/server/power/PowerManagerService$WakeLock;'
WORK_SOURCE = 'Landroid/os/WorkSource;'

METHODS = {
    AM + 'ActivityManagerService;': {
        'systemReady': '(Ljava/lang/Runnable;Lcom/android/server/utils/TimingsTraceAndSlog;)V', 'updateOomAdjLocked': '(I)V',
        'getFreezer': f'(){FREEZER}',
    },
    AM + 'ActivityManagerService$OomAdjusterCallback;': {'onProcessFreezabilityChanged': f'({STATE}ZIZII)V'},
    WM + 'MirrorActiveUids;': {'onUidActive': '(II)V', 'onUidInactive': '(I)V', 'onUidProcStateChanged': '(II)V'},
    TRACKER: {'onAnyActivityVisible': f'({WP})V', 'onAllActivitiesInvisible': f'({WP})V',
              'removeProcess': f'({WP}){WM}VisibleActivityProcessTracker$CpuTimeRecord;'},
    AM + 'psc/OomAdjuster;': {'applyOomAdjLSP': f'({STATE}Z)V', 'getFreezePolicy': f'({STATE})Z'},
    OPTIMIZER: {'freezeAppAsyncLSP': f'({PROC})V', 'updateEarliestFreezableTime': f'({PROC}J)J',
                'unfreezeAppLSP': f'({PROC}I)V', 'unfreezeAppInternalLSP': f'({PROC}IZ)Z',
                'onCleanupApplicationRecordLocked': f'({PROC})V', 'useFreezer': '()Z'},
    CACHED_RECORD: {'isFrozen': '()Z', 'setLastUsedTimeout': '(J)V'},
    FREEZER: {'setProcessFrozen': '(IIZ)V', 'freezeBinder': '(IZI)I'},
    FREEZE_HANDLER: {'freezeProcess': f'({PROC})V'},
    ANDROID_PROCESS: {'getUidForPid': '(I)I', 'setProcessFrozen': '(IIZ)V'},
    STATE: {'getCurAdj': '()I', 'setCurAdj': '(I)V', 'getCurProcState': '()I', 'setCurProcState': '(I)V'},
    PROC: {'getPid': '()I', 'getStartSeq': '()J', 'getThread': f'(){THREAD}', 'getApplicationUid': '()I',
           'killLocked': f'({STRING * 2}IILandroid/app/ApplicationExitInfo$AnrInfo;ZZ)V'},
    LOC + ';': {'getName': f'(){STRING}'},
    LOC + '$Registration;': {'onActive': '()V', 'onInactive': '()V', 'getIdentity': f'(){IDENTITY}',
                              'getRequest': '()Landroid/location/LocationRequest;'},
    AUDIO: {'getClientUid': '()I', 'getClientPid': '()I', 'getSessionId': '()I',
            'getPlayerInterfaceId': '()I', 'isActive': '()Z'},
    SERVICE_MANAGER: {'checkService': f'({STRING})Landroid/os/IBinder;'},
    AM + 'BroadcastQueueImpl;': {'enqueueBroadcastLocked': f'({BR})V'},
    AM + 'BroadcastSkipPolicy;': {'shouldSkipMessage': f'({BR}Landroid/content/pm/ResolveInfo;Z){STRING}'},
    SERVICES: {
        'startServiceLocked': f'({THREAD}{INTENT}{STRING}IIZ{STRING * 2}ILandroid/app/BackgroundStartPrivileges;ZI{STRING * 2})Landroid/content/ComponentName;',
        'retrieveServiceLocked': f'({INTENT}{STRING}ZI{STRING * 3}IIIZZZZLandroid/app/ForegroundServiceDelegationOptions;ZZZ){LOOKUP}',
    },
    WM + 'ActivityStarter;': {'executeRequest': f'({START_REQUEST})I'},
    PROVIDER: {'checkContentProviderPermission': f'(Landroid/content/pm/ProviderInfo;IIIZ{STRING}){STRING}'},
    POWER: {'systemReady': '()V', 'updatePowerStateLocked': '()V', 'updateWakeLockDisabledStatesLocked': '()V',
            'setWakeLockDisabledStateLocked': f'({WAKE_LOCK})Z'},
    WAKE_LOCK: {'setDisabled': '(Z)Z', 'updateWorkSource': f'({WORK_SOURCE})V'},
}

FIELDS = {
    AM + 'ActivityManagerService;': {'mContext': CONTEXT, 'mProcLock': AM + 'ActivityManagerGlobalLock;',
                                   'mCachedAppOptimizer': OPTIMIZER},
    WP: {'mUid': 'I'},
    FREEZE_HANDLER: {'this$0': OPTIMIZER},
    STATE: {'uid': 'I', 'processName': STRING}, PROC: {'info': APP, 'mOptRecord': CACHED_RECORD},
    LOC + '$Registration;': {'this$0': LOC + ';'},
    BR: {'callingUid': 'I', 'realCallingUid': 'I', 'intent': INTENT, 'callerPackage': STRING, 'callerApp': PROC},
    AM + 'BroadcastFilter;': {'owningUid': 'I', 'packageName': STRING},
    LOOKUP: {'record': SERVICE},
    SERVICE: {'appInfo': APP},
    START_REQUEST: {'caller': THREAD, 'callingUid': 'I', 'realCallingUid': 'I', 'callingPackage': STRING,
                    'originatingPendingIntent': AM + 'PendingIntentRecord;',
                    'activityInfo': 'Landroid/content/pm/ActivityInfo;'},
    'Landroid/app/ActivityManager;': {'START_ABORTED': 'I'},
    POWER: {'mLock': 'Ljava/lang/Object;', 'mWakeLocks': 'Ljava/util/ArrayList;'},
    WAKE_LOCK: {'mFlags': 'I', 'mOwnerUid': 'I', 'mPackageName': STRING,
                'mWorkSource': WORK_SOURCE, 'mDisabled': 'Z'},
}


def verify(base):
    targets = set(METHODS) | set(FIELDS)
    classes, code = read_with_code(base / 'services.jar', targets)
    dex.TARGETS = targets
    classes.update(dex.read_archive(base / 'framework.jar'))
    for kind, expected in (('methods', METHODS), ('fields', FIELDS)):
        for owner, members in expected.items():
            actual = {(m['name'], m.get('descriptor', m.get('type'))) for m in classes.get(owner, {}).get(kind, [])}
            missing = set(members.items()) - actual
            if missing:
                raise AssertionError(f'{base.name}: {owner} missing {sorted(missing)}')
    extra = 'II' if base.name == 'pixel-qpr2-20261113' else ''
    bindings = {m['descriptor'] for m in classes[SERVICES]['methods'] if m['name'] == 'bindServiceLocked'}
    assert f'({THREAD}{IBINDER}{INTENT}{STRING}Landroid/app/IServiceConnection;J{STRING}ZI{STRING}{THREAD}{STRING}I{extra})I' in bindings
    providers = {m['descriptor'] for m in classes[PROVIDER]['methods'] if m['name'] == 'getContentProviderImpl'}
    assert f'({THREAD}{STRING}{IBINDER}I{"I" if extra else ""}{STRING * 2}ZI){PROVIDER_HOLDER}' in providers
    skip = {m['descriptor'] for m in classes[AM + 'BroadcastSkipPolicy;']['methods'] if m['name'] == 'shouldSkipMessage'}
    assert f'({BR}{AM}BroadcastFilter;Z){STRING}' in skip
    refs = instructions(code, POWER, 'setWakeLockDisabledStateLocked', f'({WAKE_LOCK})Z')
    assert any(call.get('owner') == WAKE_LOCK and call['name'] == 'setDisabled' and call['descriptor'] == '(Z)Z'
               for ref in refs if (call := ref.get('method'))), (base.name, 'native wake lock decision')
    # CallerIdentity is outside the supplied definitions. The location service's
    # own invoke establishes the exact dependency without guessing its ABI.
    refs = instructions(code, LOC + '$Registration;', 'onRegister', '()V')
    assert any(call.get('owner') == IDENTITY and call['name'] == 'getUid' and call['descriptor'] == '()I'
               for ref in refs if (call := ref.get('method'))), (base.name, 'location identity UID invoke')
    refs = instructions(code, OPTIMIZER, 'freezeAppAsyncLSP', f'({PROC})V')
    assert any(call.get('owner') == OPTIMIZER and call['name'] == 'updateEarliestFreezableTime' and call['descriptor'] == f'({PROC}J)J'
               for ref in refs if (call := ref.get('method'))), (base.name, 'ordinary freeze scheduling')
    refs = instructions(code, OPTIMIZER, 'freezeAppAsyncLSP', f'({PROC}J)V')
    assert any(call.get('owner') == OPTIMIZER and call['name'] == 'freezeAppAsyncInternalLSP'
               for ref in refs if (call := ref.get('method'))), (base.name, 'strict freeze scheduling')
    for owner, name, sig in ((FREEZE_HANDLER, 'freezeProcess', f'({PROC})V'),
                             (OPTIMIZER, 'unfreezeAppInternalLSP', f'({PROC}IZ)Z')):
        refs = instructions(code, owner, name, sig)
        operations = [call['name'] for ref in refs if (call := ref.get('method')) and call['owner'] == FREEZER]
        assert operations.index('freezeBinder') < operations.index('setProcessFrozen'), (base.name, name)
    refs = instructions(code, FREEZER, 'setProcessFrozen', '(IIZ)V')
    assert any(call.get('owner') == ANDROID_PROCESS and call['name'] == 'setProcessFrozen' and call['descriptor'] == '(IIZ)V'
               for ref in refs if (call := ref.get('method'))), (base.name, 'native API path')


def main():
    root = Path(__file__).resolve().parents[1] / 'verification'
    hosts = (root / 'navbar-system', root / 'pixel-qpr2-20261113')
    missing = [str(base / name) for base in hosts for name in ('services.jar', 'framework.jar') if not (base / name).is_file()]
    if missing:
        print('SKIP: missing archives: ' + ', '.join(missing))
        return 2
    for base in hosts:
        verify(base)
        print(f'PASS: {base.name} background-management ABI')
    return 0


if __name__ == '__main__':
    sys.exit(main())
