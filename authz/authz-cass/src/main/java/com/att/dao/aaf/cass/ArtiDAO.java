/*******************************************************************************
 * Copyright (c) 2016 AT&T Intellectual Property. All rights reserved.
 *******************************************************************************/
package com.att.dao.aaf.cass;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.att.authz.env.AuthzTrans;
import com.att.authz.layer.Result;
import com.att.dao.Bytification;
import com.att.dao.CassDAOImpl;
import com.att.dao.Loader;
import com.att.dao.Streamer;
import com.att.inno.env.util.Chrono;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Row;

/**
 * CredDAO manages credentials. 
 * Date: 7/19/13
 */
public class ArtiDAO extends CassDAOImpl<AuthzTrans,ArtiDAO.Data> {
    public static final String TABLE = "artifact";
    
    private HistoryDAO historyDAO;
    private PSInfo psByMechID,psByMachine;
	
    public ArtiDAO(AuthzTrans trans, Cluster cluster, String keyspace) {
        super(trans, ArtiDAO.class.getSimpleName(),cluster, keyspace, Data.class,TABLE, readConsistency(trans,TABLE), writeConsistency(trans,TABLE));
        init(trans);
    }

    public ArtiDAO(AuthzTrans trans, HistoryDAO hDao, CacheInfoDAO ciDao) {
        super(trans, ArtiDAO.class.getSimpleName(),hDao, Data.class,TABLE, readConsistency(trans,TABLE), writeConsistency(trans,TABLE));
        historyDAO = hDao;
        init(trans);
    }

    public static final int KEYLIMIT = 2;
	public static class Data implements Bytification {
		public String       			mechid;
		public String       			machine;
        private Set<String>      		type;
        public String					sponsor;
        public String					ca;
        public String					dir;
        public String					appName;
        public String					os_user;
        public String					notify;
        public Date      				expires;
        public int						renewDays;
        
//      // Getters
		public Set<String> type(boolean mutable) {
			if (type == null) {
				type = new HashSet<String>();
			} else if (mutable && !(type instanceof HashSet)) {
				type = new HashSet<String>(type);
			}
			return type;
		}


		@Override
		public ByteBuffer bytify() throws IOException {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ArtifactLoader.deflt.marshal(this,new DataOutputStream(baos));
			return ByteBuffer.wrap(baos.toByteArray());
		}
		
		@Override
		public void reconstitute(ByteBuffer bb) throws IOException {
			ArtifactLoader.deflt.unmarshal(this, toDIS(bb));
		}

		public String toString() {
			return mechid + ' ' + machine + ' ' + Chrono.dateTime(expires);
		}
    }

    private static class ArtifactLoader extends Loader<Data> implements Streamer<Data>{
		public static final int MAGIC=95829343;
    	public static final int VERSION=1;
    	public static final int BUFF_SIZE=48; // Note: 

    	public static final ArtifactLoader deflt = new ArtifactLoader(KEYLIMIT);
    	public ArtifactLoader(int keylimit) {
            super(keylimit);
        }

    	@Override
        public Data load(Data data, Row row) {
            data.mechid = row.getString(0);
            data.machine = row.getString(1);
            data.type = row.getSet(2, String.class);
            data.sponsor = row.getString(3);
            data.ca = row.getString(4);
            data.dir = row.getString(5);
            data.appName = row.getString(6);
            data.os_user = row.getString(7);
            data.notify = row.getString(8);
            data.expires = row.getDate(9);
            data.renewDays = row.getInt(10);
            return data;
        }

        @Override
        protected void key(final Data data, final int idx, Object[] obj) {
        	int i;
            obj[i=idx] = data.mechid;
            obj[++i] = data.machine;
        }

        @Override
        protected void body(final Data data, final int idx, Object[] obj) {
            int i;
            obj[i=idx] = data.type;
            obj[++i] = data.sponsor;
            obj[++i] = data.ca;
            obj[++i] = data.dir;
            obj[++i] = data.appName;
            obj[++i] = data.os_user;
            obj[++i] = data.notify;
            obj[++i] = data.expires;
            obj[++i] = data.renewDays;
        }

		@Override
		public void marshal(Data data, DataOutputStream os) throws IOException {
			writeHeader(os,MAGIC,VERSION);
			writeString(os, data.mechid);
			writeString(os, data.machine);
			os.writeInt(data.type.size());
			for(String s : data.type) {
				writeString(os, s);
			}
			writeString(os, data.sponsor);
			writeString(os, data.ca);
			writeString(os, data.dir);
			writeString(os, data.appName);
			writeString(os, data.os_user);
			writeString(os, data.notify);
			os.writeLong(data.expires==null?-1:data.expires.getTime());
			os.writeInt(data.renewDays);
		}

		@Override
		public void unmarshal(Data data, DataInputStream is) throws IOException {
			/*int version = */readHeader(is,MAGIC,VERSION);
			// If Version Changes between Production runs, you'll need to do a switch Statement, and adequately read in fields
			byte[] buff = new byte[BUFF_SIZE];
			data.mechid = readString(is,buff);
			data.machine = readString(is,buff);
			int size = is.readInt();
			data.type = new HashSet<String>(size);
			for(int i=0;i<size;++i) {
				data.type.add(readString(is,buff));
			}
			data.sponsor = readString(is,buff);
			data.ca = readString(is,buff);
			data.dir = readString(is,buff);
			data.appName = readString(is,buff);
			data.os_user = readString(is,buff);
			data.notify = readString(is,buff);
			long l = is.readLong();
			data.expires = l<0?null:new Date(l);
			data.renewDays = is.readInt();
		}
    }

    private void init(AuthzTrans trans) {
        // Set up sub-DAOs
        if(historyDAO==null) {
        	historyDAO = new HistoryDAO(trans,this);
        }
        
        String[] helpers = setCRUD(trans, TABLE, Data.class, ArtifactLoader.deflt);

		psByMechID = new PSInfo(trans, SELECT_SP + helpers[FIELD_COMMAS] + " FROM " + TABLE + 
				" WHERE mechid = ?", new ArtifactLoader(1) {
			@Override
			protected void key(Data data, int idx, Object[] obj) {
				obj[idx]=data.type;
			}
		},readConsistency);

		psByMachine = new PSInfo(trans, SELECT_SP + helpers[FIELD_COMMAS] + " FROM " + TABLE + 
				" WHERE machine = ?", new ArtifactLoader(1) {
			@Override
			protected void key(Data data, int idx, Object[] obj) {
				obj[idx]=data.type;
			}
		},readConsistency);

    }
    
	
    public Result<List<Data>> readByMechID(AuthzTrans trans, String mechid) {
		return psByMechID.read(trans, R_TEXT, new Object[]{mechid});
	}

	public Result<List<ArtiDAO.Data>> readByMachine(AuthzTrans trans, String machine) {
		return psByMachine.read(trans, R_TEXT, new Object[]{machine});
	}

	/**
     * Log Modification statements to History
     *
     * @param modified        which CRUD action was done
     * @param data            entity data that needs a log entry
     * @param overrideMessage if this is specified, we use it rather than crafting a history message based on data
     */
    @Override
    protected void wasModified(AuthzTrans trans, CRUD modified, Data data, String ... override) {
    	boolean memo = override.length>0 && override[0]!=null;
    	boolean subject = override.length>1 && override[1]!=null;

        HistoryDAO.Data hd = HistoryDAO.newInitedData();
        hd.user = trans.user();
        hd.action = modified.name();
        hd.target = TABLE;
        hd.subject = subject?override[1]: data.mechid;
        hd.memo = memo
                ? String.format("%s by %s", override[0], hd.user)
                : String.format("%sd %s for %s",modified.name(),data.mechid,data.machine);
        // Detail?
   		if(modified==CRUD.delete) {
        			try {
        				hd.reconstruct = data.bytify();
        			} catch (IOException e) {
        				trans.error().log(e,"Could not serialize CredDAO.Data");
        			}
        		}

        if(historyDAO.create(trans, hd).status!=Status.OK) {
        	trans.error().log("Cannot log to History");
        }
    }
}
