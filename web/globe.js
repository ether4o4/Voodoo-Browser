import * as THREE from 'three';
import {createRenderScheduler} from './render-scheduler.mjs';

const colors={cyan:'#8bbac4',pink:'#ca96b5',green:'#acc19c',gold:'#d2b580'};
const radius=1.22;
export function coordinates(lat,lon,r=radius){const phi=(lon+180)*Math.PI/180,theta=(90-lat)*Math.PI/180;return new THREE.Vector3(-r*Math.cos(phi)*Math.sin(theta),r*Math.cos(theta),r*Math.sin(phi)*Math.sin(theta));}
export function locationAt(point){const n=point.clone().normalize();let phi=Math.atan2(n.z,-n.x);if(phi<0)phi+=Math.PI*2;return {lat:Math.max(-85,Math.min(85,Math.asin(n.y)*180/Math.PI)),lon:phi*180/Math.PI-180};}

// Cloth and relief are generated locally. No images, locations or pins leave the app.
function fabric(land){
  const canvas=document.createElement('canvas'),bump=document.createElement('canvas');canvas.width=bump.width=1024;canvas.height=bump.height=512;
  const c=canvas.getContext('2d'),b=bump.getContext('2d');c.fillStyle='#69546c';c.fillRect(0,0,1024,512);b.fillStyle='#777';b.fillRect(0,0,1024,512);
  for(let panel=0;panel<8;panel++){const x=panel*128;c.fillStyle=panel%3===0?'#85627a':panel%3===1?'#655269':'#745a73';c.beginPath();c.moveTo(x,0);c.bezierCurveTo(x+65,150,x-35,370,x,512);c.lineTo(x+128,512);c.bezierCurveTo(x+93,370,x+193,150,x+128,0);c.closePath();c.fill();
    c.strokeStyle='#c59bb4';c.lineWidth=2;b.strokeStyle='#c0c0c0';b.lineWidth=2;for(let y=12;y<512;y+=19){const sx=x+17*Math.sin(y/512*Math.PI*2);for(const ctx of [c,b]){ctx.beginPath();ctx.moveTo(sx-5,y-4);ctx.lineTo(sx+5,y+4);ctx.moveTo(sx+5,y-4);ctx.lineTo(sx-5,y+4);ctx.stroke();}}}
  for(const feature of land.features){const polys=feature.geometry.type==='Polygon'?[feature.geometry.coordinates]:feature.geometry.coordinates;for(const poly of polys){const path=new Path2D();for(const ring of poly){ring.forEach(([lon,lat],i)=>{const x=(lon+180)/360*1024,y=(90-lat)/180*512;if(i===0)path.moveTo(x,y);else path.lineTo(x,y);});path.closePath();}
      c.fillStyle='#9ebbb0';c.fill(path,'evenodd');b.fillStyle='#a8a8a8';b.fill(path,'evenodd');c.save();c.clip(path,'evenodd');c.strokeStyle='#829f97';c.lineWidth=.8;for(let line=-512;line<1536;line+=23){c.beginPath();c.moveTo(line,0);c.lineTo(line+512,512);c.moveTo(line,0);c.lineTo(line-512,512);c.stroke();}c.restore();c.strokeStyle='#b7cec1';c.lineWidth=2.3;c.setLineDash([2,3]);c.stroke(path);c.setLineDash([]);b.strokeStyle='#e0e0e0';b.lineWidth=3;b.stroke(path);}}
  // A small woven pattern supplies a soft thread-scale bump over the patch seams.
  for(let y=0;y<512;y+=3)for(let x=0;x<1024;x+=3){const dark=(x+y)%2===0;c.fillStyle=dark?'#30243125':'#eee5df18';c.fillRect(x,y,2,1);b.fillStyle=dark?'#33333355':'#ffffff44';b.fillRect(x,y,2,1);}
  const map=new THREE.CanvasTexture(canvas),relief=new THREE.CanvasTexture(bump);map.colorSpace=THREE.SRGBColorSpace;return {map,relief};
}

export async function create(container,{onSelect=()=>{},onMove=()=>{}}={}){
  const response=await fetch('vendor/land.geojson');if(!response.ok)throw Error('Globe map unavailable.');const land=await response.json();
  const renderer=new THREE.WebGLRenderer({antialias:true,alpha:true,powerPreference:'low-power'});renderer.setPixelRatio(Math.min(devicePixelRatio||1,1.6));renderer.outputColorSpace=THREE.SRGBColorSpace;renderer.setClearColor(0,0);renderer.domElement.setAttribute('aria-hidden','true');container.prepend(renderer.domElement);
  const scene=new THREE.Scene(),camera=new THREE.PerspectiveCamera(43,1,.1,40);camera.position.set(0,0,4.45);
  scene.add(new THREE.HemisphereLight(0xf5e7ed,0x4b4559,2));const light=new THREE.DirectionalLight(0xfff1e0,2.6);light.position.set(-3,4,5);scene.add(light);const fill=new THREE.DirectionalLight(0xa9c5c2,1);fill.position.set(3,-1,2);scene.add(fill);
  const world=new THREE.Group();world.rotation.set(.08,-.35,-.12);scene.add(world);const textures=fabric(land),material=new THREE.MeshStandardMaterial({map:textures.map,bumpMap:textures.relief,bumpScale:.035,roughness:1,metalness:0});
  const sphere=new THREE.Mesh(new THREE.SphereGeometry(radius,72,48),material);world.add(sphere);
  const pinRoot=new THREE.Group();world.add(pinRoot);const headGeometry=new THREE.SphereGeometry(.06,16,12),stemGeometry=new THREE.CylinderGeometry(.007,.006,.29,7),stemMaterial=new THREE.MeshStandardMaterial({color:0xbab5b5,metalness:.65,roughness:.55});
  const raycaster=new THREE.Raycaster(),pointer=new THREE.Vector2();const reduced=matchMedia('(prefers-reduced-motion: reduce)').matches;let pinMeshes=[],active=true,visible=true,rotating=!reduced,disposed=false,orbit=8,moving=null,selected=null,down=null;
  const scheduler=createRenderScheduler({render:tick,canRender:()=>active&&visible&&!document.hidden,isInteractive:()=>Boolean(down),isAnimating:()=>rotating&&!down&&!moving||pinMeshes.some(mesh=>mesh.userData.inserting)});
  function resize(){const width=Math.max(1,container.clientWidth),height=Math.max(1,container.clientHeight);renderer.setSize(width,height,false);camera.aspect=width/height;camera.updateProjectionMatrix();scheduler.invalidate();}
  const observer=new ResizeObserver(resize);observer.observe(container);resize();
  const visibility=new IntersectionObserver(entries=>{visible=entries[0].isIntersecting;if(!visible)down=null;scheduler.refresh();});visibility.observe(container);
  function tick(now,dt){
    if(rotating&&!down&&!moving){world.rotation.y+=dt*.07;orbit=(orbit+dt*.7)%50;container.querySelector('#orbit-front')?.setAttribute('startOffset',orbit+'%');container.querySelector('#orbit-back')?.setAttribute('startOffset',(orbit+50)+'%');}
    for(const mesh of pinMeshes){if(mesh.userData.inserting){const t=Math.min(1,(now-mesh.userData.start)/650),offset=.55*Math.pow(1-t,3);mesh.position.copy(mesh.userData.normal).multiplyScalar(radius+offset);if(t===1)mesh.userData.inserting=false;}}
    renderer.render(scene,camera);
  }
  function clearPins(){for(const pin of pinMeshes){pin.children[1].material.dispose();}pinRoot.clear();pinMeshes=[];}
  function setPins(pins,newId){clearPins();for(const pin of pins){const mesh=new THREE.Group(),stem=new THREE.Mesh(stemGeometry,stemMaterial),head=new THREE.Mesh(headGeometry,new THREE.MeshStandardMaterial({color:colors[pin.group]||colors.cyan,roughness:.58,metalness:.05}));stem.position.y=.125;head.position.y=.285;head.userData.pinId=pin.id;stem.userData.pinId=pin.id;mesh.add(stem,head);const normal=coordinates(pin.lat,pin.lon,1);mesh.position.copy(normal).multiplyScalar(radius);mesh.quaternion.setFromUnitVectors(new THREE.Vector3(0,1,0),normal);mesh.userData={pinId:pin.id,normal,inserting:pin.id===newId&&!reduced,start:performance.now()};if(mesh.userData.inserting)mesh.position.copy(normal).multiplyScalar(radius+.55);pinRoot.add(mesh);pinMeshes.push(mesh);}select(selected);}
  function select(id){selected=id;for(const mesh of pinMeshes)mesh.children[1].scale.setScalar(mesh.userData.pinId===id?1.35:1);scheduler.invalidate();}
  function hit(e){const r=renderer.domElement.getBoundingClientRect();pointer.set((e.clientX-r.left)/r.width*2-1,-(e.clientY-r.top)/r.height*2+1);camera.updateMatrixWorld();world.updateMatrixWorld(true);raycaster.setFromCamera(pointer,camera);return raycaster.intersectObjects([sphere,...pinMeshes.flatMap(p=>p.children)],false)[0];}
  function pointerDown(e){if(e.button!==0)return;down={x:e.clientX,y:e.clientY,lastX:e.clientX,lastY:e.clientY,dragged:false};renderer.domElement.setPointerCapture(e.pointerId);scheduler.refresh();}
  function pointerMove(e){if(!down)return;const dx=e.clientX-down.lastX,dy=e.clientY-down.lastY;if(Math.hypot(e.clientX-down.x,e.clientY-down.y)>6)down.dragged=true;if(down.dragged){world.rotation.y+=dx*.008;world.rotation.x=Math.max(-.8,Math.min(.8,world.rotation.x+dy*.006));scheduler.invalidate();}down.lastX=e.clientX;down.lastY=e.clientY;}
  function pointerUp(e){const click=down&&!down.dragged;down=null;scheduler.refresh();if(!click)return;const h=hit(e);if(!h)return;if(moving){const pos=locationAt(world.worldToLocal(h.point.clone()));onMove(moving,pos.lat,pos.lon);}else if(h.object.userData.pinId)onSelect(h.object.userData.pinId);}
  function pointerCancel(){down=null;scheduler.refresh();}
  renderer.domElement.addEventListener('pointerdown',pointerDown);renderer.domElement.addEventListener('pointermove',pointerMove);renderer.domElement.addEventListener('pointerup',pointerUp);renderer.domElement.addEventListener('pointercancel',pointerCancel);renderer.domElement.addEventListener('lostpointercapture',pointerCancel);
  function visibilityChanged(){if(document.hidden)down=null;scheduler.refresh();}
  document.addEventListener('visibilitychange',visibilityChanged);function dispose(){if(disposed)return;disposed=true;scheduler.dispose();observer.disconnect();visibility.disconnect();document.removeEventListener('visibilitychange',visibilityChanged);removeEventListener('pagehide',dispose);renderer.domElement.removeEventListener('pointerdown',pointerDown);renderer.domElement.removeEventListener('pointermove',pointerMove);renderer.domElement.removeEventListener('pointerup',pointerUp);renderer.domElement.removeEventListener('pointercancel',pointerCancel);renderer.domElement.removeEventListener('lostpointercapture',pointerCancel);clearPins();headGeometry.dispose();stemGeometry.dispose();stemMaterial.dispose();sphere.geometry.dispose();material.dispose();textures.map.dispose();textures.relief.dispose();renderer.dispose();renderer.domElement.remove();}addEventListener('pagehide',dispose,{once:true});scheduler.invalidate();
  return {setPins,select,move:id=>{moving=id;select(id);},rotate:value=>{rotating=value;scheduler.invalidate();},setActive:value=>{active=value;if(!active)down=null;scheduler.refresh();},dispose};
}
