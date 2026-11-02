from pathlib import Path
from playwright.sync_api import sync_playwright
import struct
r=Path(__file__).resolve().parent
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox']);page=b.new_page(viewport={'width':1100,'height':1100});errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.set_content((r/'MediaLoader_video_editor_reference_v2.html').read_text());page.locator('#fileInput').set_input_files(str(r/'test-media.mp4'));page.wait_for_function('S.clips[0].url && S.clips[0].duration<2');page.evaluate("chooseSection('main');enter('audio-save')")
 with page.expect_download() as out:page.locator('[data-action=apply]').click()
 path=r/'test-audio.wav';out.value.save_as(str(path));data=path.read_bytes();assert data[:4]==b'RIFF' and data[8:12]==b'WAVE';assert struct.unpack_from('<I',data,40)[0]>1000
 page.evaluate("chooseSection('clip');seek(0)");page.locator('#dock [data-action=trim]').click();assert '0.5' in page.locator('#toast').inner_text();assert page.evaluate('S.clips.length')==1
 page.evaluate('seek(.6)');page.locator('#dock [data-action=trim]').click();assert page.evaluate('S.clips.length')==2
 page.locator('#dock [data-action=copy]').click();assert page.evaluate('S.clips.length')==3
 page.locator('#dock [data-action=delete-clip]').click();assert page.evaluate('S.clips.length')==2
 assert not errors,errors
 print('Local H264/AAC media load, real PCM WAV extraction, 0.5s split guard, split/copy/delete: PASS. JS errors:',errors)
 b.close()
