from pathlib import Path
from playwright.sync_api import sync_playwright
import sys
r=Path(__file__).resolve().parent;html=Path(sys.argv[1]) if len(sys.argv)>1 else r/'MediaLoader_video_editor_reference_v7.html'
with sync_playwright() as p:
 b=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox']);page=b.new_page(viewport={'width':1120,'height':1060});errors=[];page.on('pageerror',lambda e:errors.append(str(e)));page.set_content(html.read_text());page.locator('#fileInput').set_input_files(r/'trim-fixture.mp4');page.wait_for_function("S.clips[0].url&&document.getElementById('video').readyState>=2")
 def drag(side,dx):
  h=page.locator(f'[data-handle={side}]');h.scroll_into_view_if_needed();box=h.bounding_box();x=box['x']+box['width']/2;y=box['y']+box['height']/2
  page.mouse.move(x,y);page.mouse.down();page.mouse.move(x+dx,y,steps=4)
  assert page.evaluate('!!handleDrag'),'Handle did not receive gesture'
  boundary=page.evaluate("(()=>{const c=S.clips[0];return "+("c.reverse?c.end:c.start" if side=='start' else "c.reverse?c.start:c.end")+"})()")
  page.wait_for_function('(t)=>Math.abs(document.getElementById("video").currentTime-t)<.06',arg=boundary,timeout=1500)
  page.mouse.up();return boundary
 page.evaluate('seek(1.2)');drag('end',-25)
 page.evaluate('seek(.9)');drag('start',15)
 # Boundary of first clip must not switch preview to the adjacent source.
 page.evaluate("trimPreview=null;S.clips=[{...S.clips[0],start:0,end:1.3},{...S.clips[0],id:'adjacent',start:0,end:3}];S.selected=0;seek(.5);render()")
 drag('end',-10);assert page.evaluate('selectedMediaId===S.clips[0].id')
 page.evaluate("trimPreview=null;S.clips=[{...S.clips[0],start:.2,end:2.7,reverse:true,speed:1.5}];S.selected=0;seek(.5);render()")
 drag('start',10);page.evaluate('seek(.7)');drag('end',10)
 # Full labels and bounded playhead at narrow phone width.
 page.set_viewport_size({'width':360,'height':800});page.evaluate("trimPreview=null;S.clips=[{id:'demo',url:null,duration:7.2,start:0,end:7.2,speed:1,reverse:false}];S.time=0;S.selected=null;chooseSection('main')")
 page.locator('#editor').screenshot(path=str(r/'17-main-icons.png'))
 for action in ['aspect','adjust']:
  label=page.locator(f'#dock [data-action={action}] span');assert label.evaluate('(e)=>e.scrollHeight<=e.clientHeight+1&&e.scrollWidth<=e.clientWidth+1')
 page.locator('#dock [data-action=adjust]').click()
 for label in page.locator('.adjust-tile .tile-label').all():
  assert label.evaluate('(e)=>{const l=e.getBoundingClientRect(),c=e.closest("button").getBoundingClientRect();return l.bottom<=c.bottom-2&&e.scrollHeight<=e.clientHeight+1}')
 page.locator('#editor').screenshot(path=str(r/'18-adjust-labels.png'))
 page.evaluate("chooseSection('clip')");page.locator('#editor').screenshot(path=str(r/'19-clip-icons.png'))
 assert page.locator('.playhead').bounding_box()['height']<100
 assert page.locator('#editorRequirements').text_content().find('our-photo-editor-mockup')>=0
 assert not errors,errors
 b.close()
print('Live trim both edges, adjacent/reversed/speed clips, 360px full labels and bounded playhead: PASS; no JS errors')
