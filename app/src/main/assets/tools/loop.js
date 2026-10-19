(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.WorkbenchLoop=api;})(globalThis,function(){
  'use strict';
  class CompletionLoop {
    constructor({sample,submit,onChange=()=>{},clock=()=>Date.now(),random=Math.random}){this.sample=sample;this.submit=submit;this.onChange=onChange;this.clock=clock;this.random=random;this.state='off';this.epoch=0;this.completed=0;this.submitted=0;this.inTick=false;}
    emit(message){this.message=message;this.onChange({state:this.state,message,completed:this.completed,submitted:this.submitted,due:this.due||0});}
    start({minDelay=5,maxDelay=15,limit=0,timeout=1200000}={}){
      if(!['off','error','done'].includes(this.state))return;
      const min=Number(minDelay),max=Number(maxDelay),cap=Number(limit);
      if(!Number.isFinite(min)||!Number.isFinite(max)||min<1||max<min||max>3600||!Number.isInteger(cap)||cap<0)throw new Error('Use a 1–3,600 second delay range and a whole-number run limit.');
      const s=this.sample();if(s.error)throw new Error(s.error);
      // Calibrate on a real pending generation; never infer completion from an idle page.
      if(!s.busy)throw new Error('Start one generation manually, then turn on the loop while it is loading.');
      this.epoch++;this.options={min,max,limit:cap,timeout};this.completed=0;this.submitted=0;this.state='waiting';this.baseline=s.ready;this.sawBusy=true;this.lastBusy=this.clock();this.started=this.clock();this.stableSince=null;this.due=0;this.emit('Watching the current generation finish.');
    }
    stop(message='Stopped. The current song can finish; no more will start.'){this.epoch++;this.state='off';this.due=0;this.emit(message);}
    fail(message){this.epoch++;this.state='error';this.due=0;this.emit(message);}
    async tick(){
      if(this.inTick||!['waiting','awaiting','cooldown'].includes(this.state))return;
      this.inTick=true;const epoch=this.epoch;
      try{
        const t=this.clock(),s=this.sample();
        if(s.error){this.fail(s.error);return;}
        if(this.state==='cooldown'){
          if(s.busy){this.state='waiting';this.baseline=s.ready;this.started=t;this.stableSince=null;this.emit('A generation is already running. Waiting for it.');return;}
          if(t<this.due)return;
          if(!s.canSubmit){this.fail('Create is unavailable. Check the page and restart the loop.');return;}
          this.baseline=s.ready;this.state='awaiting';this.started=t;this.stableSince=null;this.emit('Starting the next generation.');
          await this.submit(()=>this.epoch===epoch&&this.state==='awaiting');
          if(this.epoch!==epoch)return;
          this.submitted++;this.emit('Waiting for the new loading state.');return;
        }
        if(t-this.started>this.options.timeout){this.fail('Completion was not confirmed within 20 minutes. Check the page before restarting.');return;}
        if(s.busy){this.state='waiting';this.sawBusy=true;this.stableSince=null;this.lastBusy=t;return;}
        if(this.state==='awaiting'){
          if(t-this.started>30000)this.fail('No new loading state appeared. Stopped to prevent duplicate requests.');
          return;
        }
        // Need BOTH the loading markers to clear AND an additional completed marker.
        if(s.ready<=this.baseline){this.stableSince=null;return;}
        if(this.stableSince===null){this.stableSince=t;return;}
        if(t-this.stableSince<3000)return;
        this.completed++;
        if(this.options.limit>0&&this.submitted>=this.options.limit){this.state='done';this.due=0;this.emit('Batch limit reached. Loop finished.');return;}
        const seconds=this.options.min+this.random()*(this.options.max-this.options.min);
        this.due=t+Math.round(seconds*1000);this.state='cooldown';this.emit('Completed. Next generation in '+Math.ceil(seconds)+' seconds.');
      }catch(e){if(this.epoch===epoch)this.fail(e.message||'The page could not be checked.');}
      finally{this.inTick=false;}
    }
  }
  return {CompletionLoop};
});
