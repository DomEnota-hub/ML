package com.domenota.medialoader.data.playback.alignment;

/** Token ownership is independent of Android startId order. An old worker may
 * finish after a new command; only the current owner can stop that service. */
public final class AlignmentServiceRunSlot {
  private String owner;
  private int lastStartId;
  public synchronized void observe(int startId){lastStartId=Math.max(lastStartId,startId);}
  public synchronized boolean claim(String token,int startId,String currentToken) {
    observe(startId);
    if(token==null || !token.equals(currentToken) || token.equals(owner))return false;
    owner=token;return true;
  }
  public synchronized boolean owns(String token){return token!=null && token.equals(owner);}
  public synchronized boolean hasOwner(){return owner!=null;}
  public synchronized String token(){return owner;}
  public synchronized Integer finish(String token) {
    if(!owns(token))return null;
    owner=null;return lastStartId;
  }
}
