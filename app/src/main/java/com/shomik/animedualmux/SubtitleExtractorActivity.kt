package com.shomik.animedualmux

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private data class SubtitleTrack(val index:Int,val codec:String,val language:String,val title:String) {
 val ext:String get() = when(codec.lowercase(Locale.ROOT)){"ass"->"ass";"ssa"->"ssa";"subrip"->"srt";"webvtt"->"vtt";"hdmv_pgs_subtitle"->"sup";else->"mks"}
 val label:String get() = "Track #"+index+" · "+language.uppercase(Locale.ROOT)+if(title.isBlank()) " · "+codec else " · "+title+" · "+codec
}
private data class ScannedVideo(val file:File,val name:String,val tracks:List<SubtitleTrack>)

class SubtitleExtractorActivity:ComponentActivity(){
 private val worker=Executors.newSingleThreadExecutor()
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{Screen()}}
 override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
 @Composable private fun Screen(){
  var videos by remember{mutableStateOf<List<ScannedVideo>>(emptyList())}; val selected=remember{mutableStateMapOf<String,Boolean>()}
  var all by remember{mutableStateOf(true)}; var busy by remember{mutableStateOf(false)}; var progress by remember{mutableStateOf(0f)}; var status by remember{mutableStateOf("Choose one MKV or select multiple files.")}; var failed by remember{mutableStateOf(false)}
  var pending by remember{mutableStateOf<Pair<ScannedVideo,SubtitleTrack>?>(null)}
  val onePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u->if(u!=null) scan(listOf(u),selected){v,s->videos=v;status=s;failed=v.isEmpty()}}
  val manyPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){us->if(us.isNotEmpty()) scan(us,selected){v,s->videos=v;status=s;failed=v.isEmpty()}}
  val saveOne=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){u->val p=pending;if(u!=null&&p!=null){busy=true;worker.execute{try{val f=extract(p.first,p.second);contentResolver.openOutputStream(u).use{out->requireNotNull(out);FileInputStream(f).use{it.copyTo(out)}};f.delete();runOnUiThread{status="Subtitle exported successfully.";progress=1f;busy=false}}catch(e:Exception){runOnUiThread{status="Export failed: "+(e.message?:"unknown error");failed=true;busy=false}}}}}
  val saveZip=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){u->if(u!=null){val chosen=videos.flatMap{v->v.tracks.filter{all||selected[key(v,it)]==true}.map{v to it}};busy=true;worker.execute{try{val z=File(cacheDir,"subs.zip");ZipOutputStream(z.outputStream().buffered()).use{zip->chosen.forEachIndexed{i,p->runOnUiThread{progress=(i.toFloat()/chosen.size.coerceAtLeast(1));status="Extracting "+(i+1)+" / "+chosen.size};val f=extract(p.first,p.second);zip.putNextEntry(ZipEntry(p.first.name.substringBeforeLast('.')+"/track_"+p.second.index+"_"+p.second.language+"."+p.second.ext));FileInputStream(f).use{it.copyTo(zip)};zip.closeEntry();f.delete()}};contentResolver.openOutputStream(u).use{o->requireNotNull(o);FileInputStream(z).use{it.copyTo(o)}};z.delete();runOnUiThread{status="Export complete: "+chosen.size+" subtitle track(s).";progress=1f;busy=false}}catch(e:Exception){runOnUiThread{status="Bulk export failed: "+(e.message?:"unknown error");failed=true;busy=false}}}}}
  MaterialTheme{Surface(Modifier.fillMaxSize()){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Text("MKV Subtitle Extractor",style=MaterialTheme.typography.headlineLarge);Text("Extract subtitle tracks locally without re-encoding video.")
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={onePicker.launch(arrayOf("video/*","application/octet-stream"))},enabled=!busy,modifier=Modifier.weight(1f)){Text("Select one MKV")};OutlinedButton(onClick={manyPicker.launch(arrayOf("video/*","application/octet-stream"))},enabled=!busy,modifier=Modifier.weight(1f)){Text("Bulk select")}}
   if(videos.isNotEmpty()){
    Text(videos.size.toString()+" file(s) · "+videos.sumOf{it.tracks.size}+" subtitle track(s)",style=MaterialTheme.typography.titleMedium)
    Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Row(verticalAlignment=Alignment.CenterVertically){RadioButton(all,{all=true});Text("Extract all")};Row(verticalAlignment=Alignment.CenterVertically){RadioButton(!all,{all=false});Text("Select tracks manually")}}}
    videos.forEach{v->Card(Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text(v.name,style=MaterialTheme.typography.titleMedium);if(v.tracks.isEmpty())Text("No subtitle tracks found.");v.tracks.forEach{t->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(selected[key(v,t)]==true,{selected[key(v,t)]=it},enabled=!all&&!busy);Text(t.label,modifier=Modifier.weight(1f))}}}}}
    val count=videos.sumOf{v->v.tracks.count{all||selected[key(v,it)]==true}}
    Button(onClick={val v=videos.singleOrNull();val ts=v?.tracks?.filter{all||selected[key(v,it)]==true}.orEmpty();if(v!=null&&ts.size==1){pending=v to ts.first();saveOne.launch(v.name.substringBeforeLast('.')+"_track_"+ts.first().index+"."+ts.first().ext)}else if(count>0)saveZip.launch("subtitles.zip")else{status="Select at least one subtitle track.";failed=true}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(videos.size==1)"Export subtitle(s)" else "Export bulk ZIP")}
   }
   if(busy)LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());Text(status,color=if(failed)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
   Text("ASS/SSA styling is retained when stream-copy is supported. PGS tracks can export as SUP; bitmap subtitles are not OCR-converted.",style=MaterialTheme.typography.bodySmall)
  }}}
 }
 private fun key(v:ScannedVideo,t:SubtitleTrack)=v.file.absolutePath+":"+t.index
 private fun scan(uris:List<Uri>,selected:SnapshotStateMap<String,Boolean>,done:(List<ScannedVideo>,String)->Unit){done(emptyList(),"Scanning selected files…");worker.execute{val found=mutableListOf<ScannedVideo>();val errs=mutableListOf<String>();uris.forEachIndexed{i,u->try{val name=contentResolver.query(u,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)else null}?:"video_"+i+".mkv";val f=File(cacheDir,"sub_"+System.currentTimeMillis()+"_"+i+".mkv");contentResolver.openInputStream(u).use{s->requireNotNull(s);f.outputStream().use{s.copyTo(it)}};val q=FFprobeKit.executeWithArguments(arrayOf("-v","error","-show_streams","-of","json",f.absolutePath));check(ReturnCode.isSuccess(q.returnCode)){"Cannot read "+name};val a=JSONObject(q.output).optJSONArray("streams");val tracks=mutableListOf<SubtitleTrack>();if(a!=null)for(j in 0 until a.length()){val x=a.getJSONObject(j);if(x.optString("codec_type")=="subtitle"){val tags=x.optJSONObject("tags")?:JSONObject();tracks+=SubtitleTrack(x.optInt("index",j),x.optString("codec_name","unknown"),tags.optString("language","und"),tags.optString("title",""))}};found+=ScannedVideo(f,name,tracks)}catch(e:Exception){errs+=e.message?:"scan failed"}};runOnUiThread{selected.clear();found.forEach{v->v.tracks.forEach{selected[key(v,it)]=true}};done(found,if(errs.isEmpty())"Scan complete. Review tracks, then export." else "Some files failed to scan: "+errs.take(2).joinToString())}}
 private fun extract(v:ScannedVideo,t:SubtitleTrack):File{val f=File(cacheDir,"track_"+t.index+"_"+System.currentTimeMillis()+"."+t.ext);val s=FFmpegKit.executeWithArguments(arrayOf("-y","-v","error","-i",v.file.absolutePath,"-map","0:"+t.index,"-c:s","copy",f.absolutePath));if(!ReturnCode.isSuccess(s.returnCode)||!f.exists()||f.length()==0L){f.delete();error("Track #"+t.index+" extraction failed: "+(s.failStackTrace?:s.output.take(200)))};return f}
}
