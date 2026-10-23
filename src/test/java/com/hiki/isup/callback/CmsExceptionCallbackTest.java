package com.hiki.isup.callback;
import com.hiki.isup.config.IsupProperties;
import com.hiki.isup.model.*;
import com.hiki.isup.sdk.NativeSdkLoader;
import com.hiki.isup.service.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CmsExceptionCallbackTest {
 @Test void exactUniqueCurrentConnectionFailsOnlyMatchingPlayback() {
  DeviceRegistry registry=new DeviceRegistry(); SessionManager manager=new SessionManager(new NativeSdkLoader(),new IsupProperties());
  try {
   DeviceInfo connection=registry.online(7,"dev","ip",null,null);
   MediaSession s=manager.create(SessionType.PLAYBACK,connection,1); s.setLUserId(7); manager.registerIsupSession(s,8);
   CmsExceptionCallback cb=new CmsExceptionCallback(manager,registry);
   cb.invoke(0x102,7,8,null); assertFalse(s.isFinished());
   cb.invoke(0x103,99,8,null); assertFalse(s.isFinished());
   cb.invoke(0x103,7,8,null); assertEquals(SessionStatus.FAILED,s.getStatus());
   MediaSession newer=manager.create(SessionType.DOWNLOAD,connection,1); newer.setLUserId(7); manager.registerIsupSession(newer,8);
   cb.invoke(0x103,7,8,null); assertFalse(newer.isFinished());
   registry.online(7,"dev","ip",null,null);
   cb.invoke(0x103,7,8,null); assertFalse(newer.isFinished());
  } finally {manager.shutdown();registry.shutdown();}
 }
 @Test void boundedOwnershipHistoryDisablesFastFailureWhenFull() throws Exception {
  DeviceRegistry registry=new DeviceRegistry(); SessionManager manager=new SessionManager(new NativeSdkLoader(),new IsupProperties());
  try {
   var field=SessionManager.class.getDeclaredField("playbackExceptionOwners"); field.setAccessible(true);
   @SuppressWarnings("unchecked") var owners=(java.util.Map<String,String>)field.get(manager);
   for(int i=0;i<10000;i++) owners.put("old:"+i,"old");
   DeviceInfo connection=registry.online(7,"dev","ip",null,null);
   MediaSession s=manager.create(SessionType.PLAYBACK,connection,1); s.setLUserId(7); manager.registerIsupSession(s,8);
   assertEquals(10000,owners.size());
   new CmsExceptionCallback(manager,registry).invoke(0x103,7,8,null); assertFalse(s.isFinished());
  } finally { manager.shutdown(); registry.shutdown(); }
 }

 @Test void duplicateExceptionDoesNotPublishAnotherTerminalTransition() {
  DeviceRegistry registry=new DeviceRegistry(); SessionManager manager=new SessionManager(new NativeSdkLoader(),new IsupProperties());
  try {
   DeviceInfo connection=registry.online(7,"dev","ip",null,null);
   MediaSession s=manager.create(SessionType.PLAYBACK,connection,1); s.setLUserId(7); manager.registerIsupSession(s,8);
   CmsExceptionCallback cb=new CmsExceptionCallback(manager,registry);
   cb.invoke(0x103,7,8,null);
   var terminalAt=s.getUpdatedAt();
   cb.invoke(0x103,7,8,null);
   assertEquals(SessionStatus.FAILED,s.getStatus()); assertSame(terminalAt,s.getUpdatedAt());
   assertEquals(0,manager.resourceSnapshot().reservedSlots());
  } finally {manager.shutdown();registry.shutdown();}
 }

 @Test void exceptionForReplacedConnectionDoesNotStopOldOrNewSession() {
  DeviceRegistry registry=new DeviceRegistry(); SessionManager manager=new SessionManager(new NativeSdkLoader(),new IsupProperties());
  try {
   DeviceInfo old=registry.online(7,"dev","ip",null,null);
   MediaSession s=manager.create(SessionType.PLAYBACK,old,1); s.setLUserId(7); manager.registerIsupSession(s,8);
   DeviceInfo current=registry.online(7,"dev","ip",null,null);
   MediaSession newer=manager.create(SessionType.DOWNLOAD,current,1); newer.setLUserId(7); manager.registerIsupSession(newer,9);
   new CmsExceptionCallback(manager,registry).invoke(0x103,7,8,null);
   assertFalse(s.isFinished()); assertFalse(newer.isFinished());
  } finally {manager.shutdown();registry.shutdown();}
 }

 @Test void tupleReuseRemainsAmbiguousAfterOldSessionHasBeenRemoved() throws Exception {
  DeviceRegistry registry=new DeviceRegistry(); SessionManager manager=new SessionManager(new NativeSdkLoader(),new IsupProperties());
  try {
   DeviceInfo connection=registry.online(7,"dev","ip",null,null);
   MediaSession old=manager.create(SessionType.PLAYBACK,connection,1); old.setLUserId(7); manager.registerIsupSession(old,8);
   manager.stop(old,SessionStatus.STOPPED,"done");
   var field=SessionManager.class.getDeclaredField("sessions"); field.setAccessible(true);
   @SuppressWarnings("unchecked") var sessions=(java.util.Map<String,MediaSession>)field.get(manager);
   sessions.remove(old.getSessionId());
   MediaSession newer=manager.create(SessionType.DOWNLOAD,connection,1); newer.setLUserId(7); manager.registerIsupSession(newer,8);
   new CmsExceptionCallback(manager,registry).invoke(0x103,7,8,null); assertFalse(newer.isFinished());
  } finally {manager.shutdown();registry.shutdown();}
 }

 @Test void exactOwnerMustMatchEvenWhenOnlyOneCurrentSessionMatchesTuple() throws Exception {
  DeviceRegistry registry=new DeviceRegistry(); SessionManager manager=new SessionManager(new NativeSdkLoader(),new IsupProperties());
  try {
   DeviceInfo connection=registry.online(7,"dev","ip",null,null);
   MediaSession s=manager.create(SessionType.PLAYBACK,connection,1); s.setLUserId(7); manager.registerIsupSession(s,8);
   var field=SessionManager.class.getDeclaredField("playbackExceptionOwners"); field.setAccessible(true);
   @SuppressWarnings("unchecked") var owners=(java.util.Map<String,String>)field.get(manager);
   owners.put("7:8","previous-session-owner");
   new CmsExceptionCallback(manager,registry).invoke(0x103,7,8,null); assertFalse(s.isFinished());
  } finally {manager.shutdown();registry.shutdown();}
 }

}
