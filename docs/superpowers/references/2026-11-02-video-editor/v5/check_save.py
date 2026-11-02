from pathlib import Path
from playwright.sync_api import sync_playwright
r=Path(__file__).resolve().parent
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
 page=b.new_page(viewport={'width':1120,'height':1060},device_scale_factor=2);errors=[]
 page.on('pageerror',lambda e:errors.append(str(e)))
 page.set_content((r/'MediaLoader_video_editor_reference_v5.html').read_text())
 page.locator('[data-action=save]').click()
 assert page.locator('[role=dialog]').is_visible()
 page.wait_for_function('saveDemoProgress>0')
 page.evaluate('clearInterval(saveDemoTimer);updateSaveProgress(68)')
 assert page.locator('[role=progressbar]').get_attribute('aria-valuenow')=='68'
 assert page.locator('#saveProgressValue').inner_text()=='68 %'
 page.locator('#editor').screenshot(path=str(r/'10-save-progress.png'))
 page.set_viewport_size({'width':360,'height':800})
 assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 box=page.locator('[role=dialog]').bounding_box();assert box['x']>=0 and box['x']+box['width']<=360
 page.evaluate('S.modal=null;renderModal()');assert page.evaluate('saveDemoTimer===null')
 page.locator('[data-action=save]').click();page.wait_for_function("S.modal===null",timeout=7000)
 assert page.evaluate('saveDemoTimer===null')
 assert 'не создаёт MP4' in page.locator('#toast').inner_text()
 assert not errors,errors
 b.close()
print('Save overlay: real click, progress updates/68% screenshot, 360px, timer cleanup, demo completion, no JS errors. PASS')
