/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.dao.aaf.cass;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.att.authz.env.AuthzTrans;
import com.att.authz.layer.Result;
import com.att.dao.Bytification;
import com.att.dao.Cached;
import com.att.dao.CassAccess;
import com.att.dao.CassDAOImpl;
import com.att.dao.DAOException;
import com.att.dao.Loader;
import com.att.dao.Streamer;
import com.att.dao.aaf.hl.Question;
import com.att.inno.env.APIException;
import com.att.inno.env.util.Split;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.exceptions.DriverException;

public class PermDAO extends CassDAOImpl<AuthzTrans,PermDAO.Data> {

	public static final String TABLE = "perm";

    public static final int CACHE_SEG = 0x40; // yields segment 0x0-0x3F
	private static final String STAR = "*";
	
	private final HistoryDAO historyDAO;
	private final CacheInfoDAO infoDAO;
	
	private PSInfo psNS, psChildren, psByType;

	public PermDAO(AuthzTrans trans, Cluster cluster, String keyspace) throws APIException, IOException {
		super(trans, PermDAO.class.getSimpleName(),cluster,keyspace,Data.class,TABLE, readConsistency(trans,TABLE), writeConsistency(trans,TABLE));
		init(trans);
		historyDAO = new HistoryDAO(trans, this);
		infoDAO = new CacheInfoDAO(trans,this);
	}

	public PermDAO(AuthzTrans trans, HistoryDAO hDAO, CacheInfoDAO ciDAO) {
		super(trans, PermDAO.class.getSimpleName(),hDAO,Data.class,TABLE, readConsistency(trans,TABLE), writeConsistency(trans,TABLE));
		historyDAO = hDAO;
		infoDAO=ciDAO;
		init(trans);
	}


	private static final int KEYLIMIT = 4;
	public static class Data extends CacheableData implements Bytification {
		public String		ns;
		public String		type;
		public String		instance;
		public String		action;
		public Set<String>  roles; 
		public String		description;

		public Data() {}
		
		public Data(NsSplit nss, String instance, String action) {
			ns = nss.ns;
			type = nss.name;
			this.instance = instance;
			this.action = action;
		}

		public String fullType() {
			return ns + '.' + type;
		}
		
		public String fullPerm() {
			return ns + '.' + type + '|' + instance + '|' + action;
		}

		public String encode() {
			return ns + '|' + type + '|' + instance + '|' + action;
		}
		
		/**
		 * Decode Perm String, including breaking into appropriate Namespace
		 * 
		 * @param trans
		 * @param q
		 * @param p
		 * @return
		 */
		public static Result<Data> decode(AuthzTrans trans, Question q, String p) {
			String[] ss = Split.splitTrim('|', p,4);
			if(ss[2]==null) {
				return Result.err(Status.ERR_BadData,"Perm Encodings must be separated by '|'");
			}
			Data data = new Data();
			if(ss[3]==null) { // older 3 part encoding must be evaluated for NS
				Result<NsSplit> nss = q.deriveNsSplit(trans, ss[0]);
				if(nss.notOK()) {
					return Result.err(nss);
				}
				data.ns=nss.value.ns;
				data.type=nss.value.name;
				data.instance=ss[1];
				data.action=ss[2];
			} else { // new 4 part encoding
				data.ns=ss[0];
				data.type=ss[1];
				data.instance=ss[2];
				data.action=ss[3];
			}
			return Result.ok(data);
		}

		/**
		 * Decode Perm String, including breaking into appropriate Namespace
		 * 
		 * @param trans
		 * @param q
		 * @param p
		 * @return
		 */
		public static Result<String[]> decodeToArray(AuthzTrans trans, Question q, String p) {
			String[] ss = Split.splitTrim('|', p,4);
			if(ss[2]==null) {
				return Result.err(Status.ERR_BadData,"Perm Encodings must be separated by '|'");
			}
			
			if(ss[3]==null) { // older 3 part encoding must be evaluated for NS
				ss[3] = ss[2];
				ss[2] = ss[1];
				Result<NsSplit> nss = q.deriveNsSplit(trans, ss[0]);
				if(nss.notOK()) {
					return Result.err(nss);
				}
				ss[1] = nss.value.name;
				ss[0] = nss.value.ns;
			}
			return Result.ok(ss);
		}

		public static Data create(NsDAO.Data ns, String name) {
			NsSplit nss = new NsSplit(ns,name);
			Data rv = new Data();
			rv.ns = nss.ns;
			String[] s = nss.name.split("\\|");
			switch(s.length) {
				case 3:
					rv.type=s[0];
					rv.instance=s[1];
					rv.action=s[2];
					break;
				case 2:
					rv.type=s[0];
					rv.instance=s[1];
					rv.action=STAR;
					break;
				default:
					rv.type=s[0];
					rv.instance = STAR;
					rv.action = STAR;
			}
			return rv;
		}
		
		public static Data create(AuthzTrans trans, Question q, String name) {
			String[] s = name.split("\\|");
			Result<NsSplit> rdns = q.deriveNsSplit(trans, s[0]);
			Data rv = new PermDAO.Data();
			if(rdns.isOKhasData()) {
				switch(s.length) {
					case 3:
						rv.type=s[1];
						rv.instance=s[2];
						rv.action=s[3];
						break;
					case 2:
						rv.type=s[1];
						rv.instance=s[2];
						rv.action=STAR;
						break;
					default:
						rv.type=s[1];
						rv.instance = STAR;
						rv.action = STAR;
				}
			}
			return rv;
		}
		
        ////////////////////////////////////////
        // Getters
        public Set<String> roles(boolean mutable) {
            if (roles == null) {
                roles = new HashSet<String>();
            } else if (mutable && !(roles instanceof HashSet)) {
                roles = new HashSet<String>(roles);
            }
            return roles;
        }

		@Override
		public int[] invalidate(Cached<?,?> cache) {
			return new int[] {
				seg(cache,ns),
				seg(cache,ns,type),
				seg(cache,ns,type,STAR),
				seg(cache,ns,type,instance,action)
			};
		}

		@Override
		public ByteBuffer bytify() throws IOException {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			PermLoader.deflt.marshal(this, new DataOutputStream(baos));
			return ByteBuffer.wrap(baos.toByteArray());
		}
		
		@Override
		public void reconstitute(ByteBuffer bb) throws IOException {
			PermLoader.deflt.unmarshal(this, toDIS(bb));
		}

		@Override
		public String toString() {
			return encode();
		}
	}
	
	private static class PermLoader extends Loader<Data> implements Streamer<Data> {
		public static final int MAGIC=283939453;
    	public static final int VERSION=1;
    	public static final int BUFF_SIZE=96;

    	public static final PermLoader deflt = new PermLoader(KEYLIMIT);
    	
		public PermLoader(int keylimit) {
			super(keylimit);
		}
		
		@Override
		public Data load(Data data, Row row) {
			// Int more efficient Match "fields" string
			data.ns = row.getString(0);
			data.type = row.getString(1);
			data.instance = row.getString(2);
			data.action = row.getString(3);
			data.roles = row.getSet(4,String.class);
			data.description = row.getString(5);
			return data;
		}

		@Override
		protected void key(Data data, int _idx, Object[] obj) {
		    	int idx = _idx;
			obj[idx]=data.ns;
			obj[++idx]=data.type;
			obj[++idx]=data.instance;
			obj[++idx]=data.action;
		}

		@Override
		protected void body(Data data, int _idx, Object[] obj) {
		    	int idx = _idx;
			obj[idx]=data.roles;
			obj[++idx]=data.description;
		}

		@Override
		public void marshal(Data data, DataOutputStream os) throws IOException {
			writeHeader(os,MAGIC,VERSION);
			writeString(os, data.ns);
			writeString(os, data.type);
			writeString(os, data.instance);
			writeString(os, data.action);
			writeStringSet(os, data.roles);
			writeString(os, data.description);
		}

		@Override
		public void unmarshal(Data data, DataInputStream is) throws IOException {
			/*int version = */readHeader(is,MAGIC,VERSION);
			// If Version Changes between Production runs, you'll need to do a switch Statement, and adequately read in fields
			byte[] buff = new byte[BUFF_SIZE];
			data.ns = readString(is, buff);
			data.type = readString(is,buff);
			data.instance = readString(is,buff);
			data.action = readString(is,buff);
			data.roles = readStringSet(is,buff);
			data.description = readString(is,buff);
		}
	}
	
	private void init(AuthzTrans trans) {
		// the 3 is the number of key fields
		String[] helpers = setCRUD(trans, TABLE, Data.class, PermLoader.deflt);
		
		// Other SELECT style statements... match with a local Method
		psByType = new PSInfo(trans, SELECT_SP + helpers[FIELD_COMMAS] + " FROM " + TABLE + 
				" WHERE ns = ? AND type = ?", new PermLoader(2) {
			@Override
			protected void key(Data data, int idx, Object[] obj) {
				obj[idx]=data.type;
			}
		},readConsistency);
		
		psNS = new PSInfo(trans, SELECT_SP + helpers[FIELD_COMMAS] + " FROM " + TABLE +
				" WHERE ns = ?", new PermLoader(1),readConsistency);
				
		psChildren = new PSInfo(trans, SELECT_SP +  helpers[FIELD_COMMAS] +  " FROM " + TABLE + 
				" WHERE ns=? AND type > ? AND type < ?", 
				new PermLoader(3) {
			@Override
			protected void key(Data data, int _idx, Object[] obj) {
			    	int idx = _idx;
				obj[idx] = data.ns;
				obj[++idx]=data.type + DOT;
				obj[++idx]=data.type + DOT_PLUS_ONE;
			}
		},readConsistency);

	}


	/**
	 * Add a single Permission to the Role's Permission Collection
	 * 
	 * @param trans
	 * @param roleFullName
	 * @param perm
	 * @param type
	 * @param action
	 * @return
	 */
	public Result<Void> addRole(AuthzTrans trans, PermDAO.Data perm, String roleFullName) {
		// Note: Prepared Statements for Collection updates aren't supported
		//ResultSet rv =
		try {
			getSession(trans).execute(UPDATE_SP + TABLE + " SET roles = roles + {'"	+ roleFullName + "'} " +
				"WHERE " +
					"ns = '" + perm.ns + "' AND " +
					"type = '" + perm.type + "' AND " +
					"instance = '" + perm.instance + "' AND " +
					"action = '" + perm.action + "';"
					);
		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
		}

		wasModified(trans, CRUD.update, perm, "Added role " + roleFullName + " to perm " +
				perm.ns + '.' + perm.type + '|' + perm.instance + '|' + perm.action);
		return Result.ok();
	}

	/**
	 * Remove a single Permission from the Role's Permission Collection
	 * @param trans
	 * @param roleFullName
	 * @param perm
	 * @param type
	 * @param action
	 * @return
	 */
	public Result<Void> delRole(AuthzTrans trans, PermDAO.Data perm, String roleFullName) {
		// Note: Prepared Statements for Collection updates aren't supported
		//ResultSet rv =
		try {
			getSession(trans).execute(UPDATE_SP + TABLE + " SET roles = roles - {'" + roleFullName + "'} " +
				"WHERE " +
					"ns = '" + perm.ns + "' AND " +
					"type = '" + perm.type + "' AND " +
					"instance = '" + perm.instance + "' AND " +
					"action = '" + perm.action + "';"
					);
		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
		}

		//TODO how can we tell when it doesn't?
		wasModified(trans, CRUD.update, perm, "Removed role " + roleFullName + " from perm " +
				perm.ns + '.' + perm.type + '|' + perm.instance + '|' + perm.action);
		return Result.ok();
	}


	
	/**
	 * Additional method: 
	 * 		Select all Permissions by Name
	 * 
	 * @param name
	 * @return
	 * @throws DAOException
	 */
	public Result<List<Data>> readByType(AuthzTrans trans, String ns, String type) {
		return psByType.read(trans, R_TEXT, new Object[]{ns, type});
	}
	
	public Result<List<Data>> readChildren(AuthzTrans trans, String ns, String type) {
		return psChildren.read(trans, R_TEXT, new Object[]{ns, type+DOT, type + DOT_PLUS_ONE});
	}

	public Result<List<Data>> readNS(AuthzTrans trans, String ns) {
		return psNS.read(trans, R_TEXT, new Object[]{ns});
	}

	/**
	 * Add description to this permission
	 * 
	 * @param trans
	 * @param ns
	 * @param type
	 * @param instance
	 * @param action
	 * @param description
	 * @return
	 */
	public Result<Void> addDescription(AuthzTrans trans, String ns, String type,
			String instance, String action, String description) {
		try {
			getSession(trans).execute(UPDATE_SP + TABLE + " SET description = '" 
				+ description + "' WHERE ns = '" + ns + "' AND type = '" + type + "'"
				+ "AND instance = '" + instance + "' AND action = '" + action + "';");
		} catch (DriverException | APIException | IOException e) {
			reportPerhapsReset(trans,e);
			return Result.err(Result.ERR_Backend, CassAccess.ERR_ACCESS_MSG);
		}

		Data data = new Data();
		data.ns=ns;
		data.type=type;
		data.instance=instance;
		data.action=action;
		wasModified(trans, CRUD.update, data, "Added description " + description + " to permission " 
				+ data.encode(), null );
		return Result.ok();
	}
	
	/**
	 * Log Modification statements to History
	 */
	@Override
	protected void wasModified(AuthzTrans trans, CRUD modified, Data data, String ... override) {
    	boolean memo = override.length>0 && override[0]!=null;
    	boolean subject = override.length>1 && override[1]!=null;

		// Need to update history
		HistoryDAO.Data hd = HistoryDAO.newInitedData();
		hd.user = trans.user();
		hd.action = modified.name();
		hd.target = TABLE;
		hd.subject = subject ? override[1] : data.fullType();
		if (memo) {
            hd.memo = String.format("%s", override[0]);
        } else {
            hd.memo = String.format("%sd %s|%s|%s", modified.name(),data.fullType(),data.instance,data.action);
        }
		
		if(modified==CRUD.delete) {
			try {
				hd.reconstruct = data.bytify();
			} catch (IOException e) {
				trans.error().log(e,"Could not serialize PermDAO.Data");
			}
		}
		
        if(historyDAO.create(trans, hd).status!=Status.OK) {
        	trans.error().log("Cannot log to History");
        }
        if(infoDAO.touch(trans, TABLE,data.invalidate(cache)).notOK()) {
        	trans.error().log("Cannot touch CacheInfo");
        }
	}
}

