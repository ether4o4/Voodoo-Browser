package com.ether4o4.voodoo;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.*;
import android.speech.tts.*;
import android.webkit.WebView;
import androidx.webkit.JavaScriptReplyProxy;
import java.util.*;

/** Only internal foreground Studio requests can own microphone or speech output. */
final class VoiceController {
    static final int PERMISSION=21;
    private final MainActivity a;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private final VoiceEpoch generation=new VoiceEpoch();
    private WebView owner;
    private JavaScriptReplyProxy reply;
    private String requestId;
    VoiceController(MainActivity activity){a=activity;}
    private boolean live(long token){return token==generation.current()&&reply!=null&&a.alive&&a.current==owner&&a.local(a.tabUrl(owner));}
    private void begin(WebView view,JavaScriptReplyProxy proxy,String id)throws Exception{
        if(a.current!=view||!a.local(a.tabUrl(view)))throw new Exception("Open Studio to use voice.");
        stop();owner=view;reply=proxy;requestId=id;
    }
    private void finish(long token,Object result,String error){if(!live(token))return;a.reply(reply,requestId,result,error);reply=null;requestId=null;owner=null;releaseRecognizer();}
    void listen(WebView view,JavaScriptReplyProxy proxy,String id)throws Exception{
        begin(view,proxy,id);
        if(a.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){generation.awaitPermission();a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},PERMISSION);return;}
        startListening(generation.current());
    }
    void permissionResult(boolean granted){if(!generation.consumePermission()||reply==null)return;if(granted)startListening(generation.current());else finish(generation.current(),null,"Microphone permission was declined.");}
    private void startListening(long token){
        if(!live(token))return;
        if(!SpeechRecognizer.isRecognitionAvailable(a)){finish(token,null,"Speech recognition is unavailable on this device.");return;}
        try{
            recognizer=SpeechRecognizer.createSpeechRecognizer(a);
            recognizer.setRecognitionListener(new RecognitionListener(){
                public void onReadyForSpeech(Bundle b){}public void onBeginningOfSpeech(){}public void onRmsChanged(float n){}public void onBufferReceived(byte[] b){}public void onEndOfSpeech(){}public void onPartialResults(Bundle b){}public void onEvent(int t,Bundle b){}
                public void onError(int code){finish(token,null,"Speech recognition ended ("+code+"). Tap Mic to retry.");}
                public void onResults(Bundle results){ArrayList<String> words=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);finish(token,words==null||words.isEmpty()?"":words.get(0),null);}
            });
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
            recognizer.startListening(intent);
        }catch(Exception e){finish(token,null,"Could not start speech recognition.");}
    }
    void speak(WebView view,JavaScriptReplyProxy proxy,String id,String text)throws Exception{
        if(text==null||text.length()>20000)throw new Exception("Voice reply exceeds 20,000 characters.");
        begin(view,proxy,id);long token=generation.current();
        if(ttsReady){speakReady(token,text);return;}
        if(tts!=null){tts.shutdown();tts=null;}
        tts=new TextToSpeech(a,status->a.runOnUiThread(()->{if(!live(token))return;if(status!=TextToSpeech.SUCCESS){finish(token,null,"Speech output is unavailable.");return;}ttsReady=true;speakReady(token,text);}));
    }
    private void speakReady(long token,String text){
        if(!live(token))return;
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){}public void onDone(String id){a.runOnUiThread(()->{if(generation.utterance(id,token))finish(token,true,null);});}public void onError(String id){a.runOnUiThread(()->{if(generation.utterance(id,token))finish(token,null,"Speech output failed.");});}});
        if(tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"voodoo-"+token)==TextToSpeech.ERROR)finish(token,null,"Speech output failed.");
    }
    private void releaseRecognizer(){if(recognizer!=null){SpeechRecognizer old=recognizer;recognizer=null;old.cancel();old.destroy();}}
    void leaving(WebView view){if(owner==view)stop();}
    void stop(){
        generation.next();
        if(reply!=null)a.reply(reply,requestId,null,"Voice stopped.");reply=null;requestId=null;owner=null;
        releaseRecognizer();
        if(tts!=null)tts.stop();
    }
    void close(){stop();if(tts!=null){tts.shutdown();tts=null;}ttsReady=false;}
}
