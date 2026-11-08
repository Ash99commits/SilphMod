package dev.silphmod.nativecheck;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;

public final class RaidFeedClient implements AutoCloseable {
    private static final long MAX_BYTES=8388608;
    private final LongSupplier clock;
    private final Entry[] entries={new Entry(),new Entry()};
    private volatile boolean closed;
    private volatile HttpsURLConnection active;
    private static final class Entry {
        String url="",etag="",modified="";
        long attempted=-1;
        volatile RaidFeed.Snapshot snapshot;
    }
    public static final class Result {
        public final RaidFeed.Snapshot snapshot;
        public final int status;
        public final boolean fetched,revalidated;
        private Result(RaidFeed.Snapshot snapshot,int status,boolean fetched,boolean revalidated){
            this.snapshot=snapshot;this.status=status;this.fetched=fetched;this.revalidated=revalidated;
        }
    }
    public RaidFeedClient(LongSupplier clock){if(clock==null)throw new IllegalArgumentException("Missing raid feed clock");this.clock=clock;}
    public static URI validateUrl(String value)throws IOException{
        try{
            URI uri=new URI(value);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getFragment()!=null||uri.getHost().length()>253||value.length()>2048)throw new IOException("Invalid raid feed URL");
            return uri;
        }catch(URISyntaxException|NullPointerException invalid){throw new IOException("Invalid raid feed URL");}
    }
    private static final class Limited extends FilterInputStream {
        long remaining=MAX_BYTES;
        Limited(InputStream in){super(in);}
        @Override public int read()throws IOException{
            int value=super.read();if(value>=0&&--remaining<0)throw new IOException("Raid feed response too large");return value;
        }
        @Override public int read(byte[] bytes,int offset,int count)throws IOException{
            int length=in.read(bytes,offset,(int)Math.min(count,Math.max(1,remaining+1)));
            if(length>0&&(remaining-=length)<0)throw new IOException("Raid feed response too large");return length;
        }
    }
    public synchronized Result refresh(int country,String url)throws IOException{
        if(closed)throw new IOException("Raid feed closed");
        if(country!=RaidFeed.MEXICO&&country!=RaidFeed.JAPAN)throw new IOException("Invalid raid country");
        URI uri=validateUrl(url);Entry entry=entries[country-1];long elapsed=clock.getAsLong();
        if(elapsed<0)throw new IOException("Invalid raid feed clock");
        if(!url.equals(entry.url)){entry.url=url;entry.etag=entry.modified="";entry.snapshot=null;entry.attempted=-1;}
        if(entry.attempted>=0&&elapsed>=entry.attempted&&elapsed-entry.attempted<RaidFeed.REFRESH_MS)return new Result(fresh(entry,elapsed),0,false,false);
        entry.attempted=elapsed;HttpsURLConnection connection=(HttpsURLConnection)uri.toURL().openConnection();active=connection;
        try{
            if(closed)throw new IOException("Raid feed closed");
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Accept-Encoding","gzip");
            connection.setRequestProperty("User-Agent","SilphMod/AutoRaid");connection.setRequestProperty("Cache-Control","no-cache");
            if(!entry.etag.isEmpty())connection.setRequestProperty("If-None-Match",entry.etag);
            if(!entry.modified.isEmpty())connection.setRequestProperty("If-Modified-Since",entry.modified);
            int status=connection.getResponseCode();long received=clock.getAsLong(),server=serverTime(connection);
            if(received<elapsed)throw new IOException("Invalid raid feed clock");
            if(status==HttpsURLConnection.HTTP_NOT_MODIFIED&&entry.snapshot!=null){
                try{entry.snapshot=entry.snapshot.revalidated(server,received);}catch(IllegalArgumentException invalid){throw new IOException("Invalid raid feed response clock");}
                return new Result(entry.snapshot,status,true,true);
            }
            if(status!=HttpsURLConnection.HTTP_OK)throw new IOException("Raid feed HTTP "+status);
            long length=connection.getContentLengthLong();if(length>MAX_BYTES)throw new IOException("Raid feed response too large");
            String encoding=connection.getContentEncoding();if(encoding!=null&&!encoding.equalsIgnoreCase("gzip")&&!encoding.equalsIgnoreCase("identity"))throw new IOException("Unsupported raid feed encoding");
            try(InputStream raw=new Limited(connection.getInputStream());InputStream decoded=new Limited("gzip".equalsIgnoreCase(encoding)?new GZIPInputStream(raw):raw)){
                RaidFeed.Snapshot snapshot=RaidFeed.read(new InputStreamReader(decoded,StandardCharsets.UTF_8),country,server,received);
                if(closed)throw new IOException("Raid feed closed");entry.snapshot=snapshot;
            }
            entry.etag=header(connection,"ETag");entry.modified=header(connection,"Last-Modified");
            return new Result(entry.snapshot,status,true,false);
        }finally{active=null;connection.disconnect();}
    }
    public RaidFeed.Snapshot snapshot(int country){
        if(closed||country<1||country>2)return null;return fresh(entries[country-1],clock.getAsLong());
    }
    private static RaidFeed.Snapshot fresh(Entry entry,long elapsed){return entry.snapshot!=null&&entry.snapshot.nowMs(elapsed)>=0?entry.snapshot:null;}
    private static String header(HttpsURLConnection connection,String name){String value=connection.getHeaderField(name);return value==null||value.length()>512?"":value;}
    private static long serverTime(HttpsURLConnection connection)throws IOException{
        long server=connection.getDate(),age=0;String value=connection.getHeaderField("Age");
        try{if(value!=null)age=Long.parseLong(value);}catch(NumberFormatException invalid){throw new IOException("Invalid raid feed age");}
        if(server<1577836800000L||server>4102444800000L||age<0||age>86400)throw new IOException("Invalid raid feed response clock");
        return server+age*1000;
    }
    @Override public void close(){closed=true;HttpsURLConnection connection=active;if(connection!=null)connection.disconnect();}
}
