#!/usr/bin/env python3
"""Opt-in temporary negative probes. Nothing from a fault run is qualified."""
import os
from pathlib import Path
mode=os.environ.get('ML_FAULT','none')
if mode=='compile':
    p=Path('app/src/test/java/com/domenota/medialoader/CiCompileFault.kt');p.write_text('package com.domenota.medialoader\nval ciIntentionalCompileFault: Int = "compile-probe"\n')
elif mode=='test':
    p=Path('app/src/test/java/com/domenota/medialoader/CiTestFault.kt');p.write_text('package com.domenota.medialoader\nclass CiTestFault { @org.junit.Test fun qualificationMustRejectFailure() { org.junit.Assert.fail("intentional CI probe") } }\n')
elif mode=='lint':
    p=Path('app/src/main/java/com/domenota/medialoader/CiLintFault.kt');p.write_text('package com.domenota.medialoader\nfun ciIntentionalNewApiFault(resolver: android.content.ContentResolver, uris: Collection<android.net.Uri>) = android.provider.MediaStore.createDeleteRequest(resolver, uris)\n')
elif mode!='none':raise ValueError('Unknown fault')
print('Negative probe:',mode)
