from pathlib import Path
from playwright.sync_api import sync_playwright
r=Path(__file__).resolve().parent
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox']);page=b.new_page(viewport={'width':1120,'height':1100});errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.set_content((r/'MediaLoader_video_editor_reference_v2.html').read_text());editor=page.locator('#editor')
 editor.screenshot(path=str(r/'01-clip.png'))
 page.locator('#dock [data-action=speed]').click();assert page.locator('#panelTitle').inner_text()=='Скорость';assert abs(float(page.locator('[data-range=speed]').input_value())-2)<.01
 editor.screenshot(path=str(r/'02-speed.png'))
 page.locator('[data-speed="2"]').click();assert page.evaluate('currentClip().speed')==2
 page.locator('[data-action=cancel]').click();assert page.evaluate('currentClip().speed')==1
 page.locator('[data-action=root]').click();page.locator('#dock [data-action=adjust]').click()
 page.locator('[data-range=exposure]').evaluate('(e)=>{e.value=25;e.dispatchEvent(new Event("input",{bubbles:true}))}');assert page.evaluate('S.adjust.exposure')==25
 editor.screenshot(path=str(r/'03-adjust.png'))
 page.locator('[data-action=cancel]').click();assert page.evaluate('S.adjust.exposure')==0
 page.locator('#dock [data-action=filters]').click();page.locator('[data-filter="popular:1"]').click();assert page.evaluate('S.filterId')=='popular:1'
 page.locator('[data-range=intensity]').evaluate('(e)=>{e.value=40;e.dispatchEvent(new Event("input",{bubbles:true}))}');assert page.evaluate('S.intensity')==40
 editor.screenshot(path=str(r/'04-effects.png'))
 page.locator('[data-action=apply]').click();page.locator('#dock [data-action=frame]').click()
 with page.expect_download() as download: page.locator('[data-action=apply]').click()
 download.value.save_as(str(r/'test-frame.png'));assert (r/'test-frame.png').stat().st_size>100
 page.set_viewport_size({'width':360,'height':800});assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
 assert not errors,errors
 print('UI paths, native speed scale, cancel restoration, modifiers, effect strength, PNG export, 360px: PASS; JS errors:',errors)
 b.close()
