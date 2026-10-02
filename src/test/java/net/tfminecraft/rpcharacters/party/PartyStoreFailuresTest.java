package net.tfminecraft.rpcharacters.party;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class PartyStoreFailuresTest {
 @TempDir Path folder;
 @Test void invalidHeadersAndRecordsNeverPartiallyLoadOrRewriteTheFile()throws Exception{var file=folder.resolve("parties.json");var store=new PartyStore(file);String id=UUID.randomUUID().toString(),leader=UUID.randomUUID().toString();for(String body:List.of("null","{}","{\"version\":2,\"parties\":[]}","{\"version\":1,\"parties\":[null]}","{\"version\":1,\"parties\":[{\"id\":\""+id+"\",\"name\":\"Guild\",\"leader\":\""+leader+"\",\"members\":[\""+leader+"\",null]}]}","{\"version\":1,\"parties\":[{\"id\":\""+id+"\",\"name\":\"Guild\",\"leader\":\""+leader+"\",\"members\":[\""+leader+"\",\""+leader+"\"]}]}")){Files.writeString(file,body);assertThrows(IOException.class,store::load);assertEquals(body,Files.readString(file));}}
 @Test void unsupportedAtomicReplacementMustNotRiskThePreviousFile()throws Exception{var file=folder.resolve("parties.json");var store=new PartyStore(file);var party=new Party(UUID.randomUUID(),"Before",UUID.randomUUID());store.save(List.of(party));String previous=Files.readString(file);try(var files=mockStatic(Files.class,c->{if(c.getMethod().getName().equals("move")){CopyOption[] options=(CopyOption[])c.getRawArguments()[2];if(Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))throw new AtomicMoveNotSupportedException("source","target","no atomic support");Files.write(file,"partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));throw new IOException("non-atomic replacement failed");}return c.callRealMethod();})){assertThrows(IOException.class,()->store.save(List.of()));}assertEquals(previous,Files.readString(file),"A failed party save must preserve the last durable membership");}
 @Test void failedCleanupMustNotHideTheOriginalWriteFailure()throws Exception{var file=folder.resolve("parties.json");var store=new PartyStore(file);store.save(List.of());String previous=Files.readString(file);var failedWrite=new IOException("disk full");var failedCleanup=new IOException("unlink failed");var staged=new AtomicReference<Path>();try{try(var files=mockStatic(Files.class,c->{if(c.getMethod().getName().equals("writeString")){staged.set(c.getArgument(0));throw failedWrite;}if(c.getMethod().getName().equals("deleteIfExists"))throw failedCleanup;return c.callRealMethod();})){assertSame(failedWrite,assertThrows(IOException.class,()->store.save(List.of())));}assertEquals(previous,Files.readString(file));assertArrayEquals(new Throwable[]{failedCleanup},failedWrite.getSuppressed());}finally{if(staged.get()!=null)Files.deleteIfExists(staged.get());}}
}
