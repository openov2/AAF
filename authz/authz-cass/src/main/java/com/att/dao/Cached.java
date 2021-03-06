/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.dao;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import com.att.authz.env.AuthzEnv;
import com.att.authz.env.AuthzTrans;
import com.att.authz.layer.Result;
import com.att.cache.Cache;
import com.att.dao.aaf.cass.Status;
import com.att.inno.env.Env;
import com.att.inno.env.Trans;

public class Cached<TRANS extends Trans, DATA extends Cacheable> extends Cache<TRANS,DATA> {
	// Java does not allow creation of Arrays with Generics in them...
	// private Map<String,Dated> cache[];
	protected final CIDAO<TRANS> info;
	
	private static Timer infoTimer;
	private Object cache[];
	public final int segSize;

	protected final String name;
	


	// Taken from String Hash, but coded, to ensure consistent across Java versions.  Also covers negative case;
	public int cacheIdx(String key) {
		int h = 0;
		for (int i = 0; i < key.length(); i++) {
		    h = 31*h + key.charAt(i);
		}
		if(h<0)h*=-1;
		return h%segSize;
	}
	
	public Cached(CIDAO<TRANS> info, String name, int segSize) {
		this.name =name;
		this.segSize = segSize;
		this.info = info;
		cache = new Object[segSize];
		// Create a new Map for each Segment, and store locally
		for(int i=0;i<segSize;++i) {
			cache[i]=obtain(name+i);
		}
	}
	
	public void add(String key, List<DATA> data) {
		@SuppressWarnings("unchecked")
		Map<String,Dated> map = ((Map<String,Dated>)cache[cacheIdx(key)]);
		map.put(key, new Dated(data));
	}


	public int invalidate(String key)  {
		int cacheIdx = cacheIdx(key);
		@SuppressWarnings("unchecked")
		Map<String,Dated> map = ((Map<String,Dated>)cache[cacheIdx]);
//		if(map.remove(key)!=null) // Not seeming to remove all the time
		if(map!=null)map.clear();
//			System.err.println("Remove " + name + " " + key);
		return cacheIdx;
	}

	public Result<Void> invalidate(int segment)  {
		if(segment<0 || segment>=cache.length) return Result.err(Status.ERR_BadData,"Cache Segment %s is out of range",Integer.toString(segment));
		@SuppressWarnings("unchecked")
		Map<String,Dated> map = ((Map<String,Dated>)cache[segment]);
		if(map!=null) {
			map.clear();
		}
		return Result.ok();
	}

	protected interface Getter<D> {
		public abstract Result<List<D>> get();
	};
	
	// TODO utilize Segmented Caches, and fold "get" into "reads"
	@SuppressWarnings("unchecked")
	public Result<List<DATA>> get(TRANS trans, String key, Getter<DATA> getter) {
		List<DATA> ld = null;
		Result<List<DATA>> rld = null;
		
		int cacheIdx = cacheIdx(key);
		Map<String, Dated> map = ((Map<String,Dated>)cache[cacheIdx]);
		
		// Check for saved element in cache
		Dated cached = map.get(key);
		// Note: These Segment Timestamps are kept up to date with DB
		Date dbStamp = info.get(trans, name,cacheIdx);
		
		// Check for cache Entry and whether it is still good (a good Cache Entry is same or after DBEntry, so we use "before" syntax)
		if(cached!=null && dbStamp.before(cached.timestamp)) {
			ld = (List<DATA>)cached.data;
			rld = Result.ok(ld);
		} else {
			rld = getter.get();
			if(rld.isOK()) { // only store valid lists
				map.put(key, new Dated(rld.value));  // successful item found gets put in cache
//			} else if(rld.status == Result.ERR_Backend){
//				map.remove(key);
			}
		}
		return rld;
	}

	/**
	 * Each Cached object has multiple Segments that need cleaning.  Derive each, and add to Cleansing Thread
	 * @param env
	 * @param dao
	 */
	public static void startCleansing(AuthzEnv env, CachedDAO<?,?,?> ... dao) {
		for(CachedDAO<?,?,?> d : dao) {  
			for(int i=0;i<d.segSize;++i) {
				startCleansing(env, d.table()+i);
			}
		}
	}


	public static<T extends Trans> void startRefresh(AuthzEnv env, CIDAO<AuthzTrans> cidao) {
		if(infoTimer==null) {
			infoTimer = new Timer("CachedDAO Info Refresh Timer");
			int minRefresh = 10*1000*60; // 10 mins Integer.parseInt(env.getProperty(CACHE_MIN_REFRESH_INTERVAL,"2000")); // 2 second minimum refresh 
			infoTimer.schedule(new Refresh(env,cidao, minRefresh), 1000, minRefresh); // note: Refresh from DB immediately
		}
	}
	
	public static void stopTimer() {
		Cache.stopTimer();
		if(infoTimer!=null) {
			infoTimer.cancel();
			infoTimer = null;
		}
	}
	
	private final static class Refresh extends TimerTask {
		private static final int maxRefresh = 2*60*10000; // 20 mins
		private AuthzEnv env;
		private CIDAO<AuthzTrans> cidao;
		private int minRefresh;
		private long lastRun;
		
		public Refresh(AuthzEnv env, CIDAO<AuthzTrans> cidao, int minRefresh) {
			this.env = env;
			this.cidao = cidao;
			this.minRefresh = minRefresh;
			lastRun = System.currentTimeMillis()-maxRefresh-1000;
		}
		
		@Override
		public void run() {
			// Evaluate whether to refresh based on transaction rate
			long now = System.currentTimeMillis();
			long interval = now-lastRun;

			if(interval < minRefresh || interval < Math.min(env.transRate(),maxRefresh)) return;
			lastRun = now;
			AuthzTrans trans = env.newTransNoAvg();
			Result<Void> rv = cidao.check(trans);
			if(rv.status!=Result.OK) {
				env.error().log("Error in CacheInfo Refresh",rv.details);
			}
			if(env.debug().isLoggable()) {
				StringBuilder sb = new StringBuilder("Cache Info Refresh: ");
				trans.auditTrail(0, sb, Env.REMOTE);
				env.debug().log(sb);
			}
		}
	}
}
