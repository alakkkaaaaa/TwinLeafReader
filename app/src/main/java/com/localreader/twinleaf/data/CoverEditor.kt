package com.localreader.twinleaf.data

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import java.io.File

object CoverEditor {
    fun source(context:Context,uri:Uri):Bitmap {
        val bytes=context.contentResolver.openInputStream(uri)?.use { input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer);if(n<0) break;require(out.size()+n<=32*1024*1024) { "图片不能超过 32 MB" };out.write(buffer,0,n) };out.toByteArray()
        } ?: error("无法读取图片")
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "无法解码图片" }
        var sample=1;while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048) sample*=2
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("图片解码失败")
        val orientation=runCatching { ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)
        val matrix=Matrix().apply { when(orientation) {
            2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f)
            5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f)
            7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)
        } }
        return if(matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
    }
    fun cropRect(width:Int,height:Int,zoom:Float,x:Float,y:Float):Rect {
        val aspect=.72f
        val baseW=minOf(width.toFloat(),height*aspect)
        val w=(baseW/zoom.coerceIn(1f,4f)).coerceAtLeast(1f);val h=w/aspect
        val left=((width-w)/2*(1+x.coerceIn(-1f,1f))).toInt().coerceAtLeast(0)
        val top=((height-h)/2*(1+y.coerceIn(-1f,1f))).toInt().coerceAtLeast(0)
        return Rect(left,top,(left+w).toInt().coerceAtMost(width),(top+h).toInt().coerceAtMost(height))
    }
    fun save(context:Context,bookId:String,bitmap:Bitmap,zoom:Float,x:Float,y:Float):String {
        val result=Bitmap.createBitmap(720,1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(result);canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap,cropRect(bitmap.width,bitmap.height,zoom,x,y),Rect(0,0,720,1000),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val directory=File(context.filesDir,"covers").apply { mkdirs() }
        val file=File(directory,"custom-${bookId.filter { it.isLetterOrDigit() }}-${System.nanoTime()}.jpg")
        file.outputStream().use { check(result.compress(Bitmap.CompressFormat.JPEG,92,it)) };result.recycle();return file.absolutePath
    }
}
