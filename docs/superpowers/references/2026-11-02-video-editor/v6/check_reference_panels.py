from pathlib import Path
from playwright.sync_api import sync_playwright
r=Path(__file__).resolve().parent
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox']);page=b.new_page(viewport={'width':1120,'height':1060},device_scale_factor=2);errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.set_content((r/'MediaLoader_video_editor_reference_v6.html').read_text());ed=page.locator('#editor')
 assert page.evaluate("S.section==='main'")
 assert page.locator('#dock [data-action=extras]').count()==1
 assert page.locator('#dock [data-action=frame]').count()==0
 ed.screenshot(path=str(r/'11-main.png'))
 page.locator('#dock [data-action=extras]').click();assert page.locator('#dock [data-action=frame]').is_visible();assert page.locator('#dock [data-action=audio-save]').is_visible();ed.screenshot(path=str(r/'12-more.png'))
 page.locator('#dock [data-action=root]').click();page.locator('#dock [data-action=aspect]').click()
 assert page.locator('[data-project-ratio]').count()==8
 page.locator('[data-project-ratio="1:1"]').click();box=page.locator('#preview').bounding_box();assert abs(box['width']/box['height']-1)<.01
 page.locator('[data-action=cancel]:visible').click();assert page.evaluate("S.projectRatio==='Исходное'")
 page.locator('#dock [data-action=aspect]').click();page.locator('[data-project-ratio="2.39:1"]').click();page.locator('[data-action=apply]:visible').click();assert page.evaluate("S.projectRatio==='2.39:1'")
 page.evaluate('undo()');assert page.evaluate("S.projectRatio==='Исходное'")
 page.locator('#dock [data-action=aspect]').click();ed.screenshot(path=str(r/'13-aspect.png'));page.locator('[data-action=cancel]:visible').click()
 page.locator('#dock [data-action="section:audio"]').click();page.locator('#dock [data-action=add-music]').click();assert page.locator('.music-library-row').count()==2
 page.locator('[data-music-preview]').first.click();page.wait_for_function('!musicPreview.paused&&musicPreview.currentTime>0');ed.screenshot(path=str(r/'14-music-picker.png'))
 page.locator('[data-music-add]').first.click();assert page.evaluate('musicPreview.paused&&S.musicClips.length===1');assert page.locator('.music-segment').count()==1
 page.evaluate('seek(2);play()');page.wait_for_function('!customAudio.paused&&customAudio.currentTime>0');page.evaluate('pause()')
 page.evaluate('seek(3)');page.locator('#dock [data-action=music-split]').click();assert page.evaluate('S.musicClips.length===2')
 page.locator('#dock [data-action=music-volume]').click();page.locator('[data-range=musicVolume]').evaluate('(e)=>{e.value=45;e.dispatchEvent(new Event("input",{bubbles:true}))}');page.locator('[data-action=apply]:visible').click();assert page.evaluate('selectedMusic().volume===45')
 ed.screenshot(path=str(r/'15-music-track.png'))
 page.locator('#dock [data-action=music-delete]').click();assert page.evaluate('S.musicClips.length===1')
 page.locator('[data-music-clip]').click();page.locator('#dock [data-action=music-copy]').click();assert page.evaluate('S.musicClips.length===2')
 page.locator('#dock [data-action=music-edit]').click();page.locator('[data-music-tab=online]').click();assert page.locator('.music-empty').is_visible();page.locator('[data-music-tab=device]').click()
 # Real file import goes through the rendered input, then appears as another track.

 import io,wave
 wav=io.BytesIO()
 with wave.open(wav,'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(bytes(32000))
 page.locator('#libraryMusicInput').set_input_files({'name':'Проба импорта.wav','mimeType':'audio/wav','buffer':wav.getvalue()})
 page.wait_for_function('musicLibrary.length===3');assert page.locator('.music-library-row').count()==3
 page.set_viewport_size({'width':360,'height':800});assert page.evaluate('document.documentElement.scrollWidth<=innerWidth');ed.screenshot(path=str(r/'16-music-mobile.png'))
 page.keyboard.press('Escape');assert page.locator('#musicPicker').count()==0
 assert not errors,errors
 b.close()
print('Main/More, 8 aspect ratios/apply/cancel/undo, music audition/import/add/playback/split/volume/delete/copy, mobile360px: PASS; no JS errors')
