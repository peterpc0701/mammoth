package org.apache.hadoop.mapred;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.mapred.CachePool.CachePoolFullException;
import org.apache.hadoop.mapred.CachePool.CacheUnit;


public class CacheFile extends InputStream {	
	private static final Log LOG = LogFactory.getLog(CacheFile.class.getName());
	private List<CacheUnit> content;
	private List<CacheUnit> rcontent;
	private final CachePool pool;
	private CacheUnit curCU = null;
	private long cursor = 0; //for read
	private long len = 0;
	private boolean delete = false;  //true: delete CU after read, but cannot skip 
	private boolean isWriteDone = false;	 
	
	int maxSpills = 0;
	//private List<CacheUnit> spills = new LinkedList<CacheUnit>();
	FSDataOutputStream output;
	private TaskAttemptID taskid;
	private static long DEFAULTRLENGTH = 0;
	private long rlen = 0;
	
	CacheFile(CachePool p, long rlength) throws IOException {
		this(p, rlength, null, false, null, -1);				
	}
	CacheFile(CachePool p, long rlength, FSDataOutputStream out, boolean asyn, 
			TaskAttemptID tid, int priority) throws IOException{
		if (rlength != DEFAULTRLENGTH) {
			rlength = size2Cap(rlength);
			content = new ArrayList<CacheUnit>((int)(rlength / CacheUnit.cap) + 1);
			rcontent = new ArrayList<CacheUnit>((int)(rlength / CacheUnit.cap) + 1);
			rcontent.addAll(p.getUnits((int)(rlength/CacheUnit.cap)));
		} else {
			content = new LinkedList<CacheUnit>();
			rcontent = new LinkedList<CacheUnit>();
		}		
		rlen = rlength;
		pool = p;
		output = out;		
		taskid = tid;		
	}
	private CacheUnit getUnit() throws CachePoolFullException {
		if (rcontent.size() != 0) {
			return rcontent.remove(0);
		} else {
			rlen += CacheUnit.cap;
			return pool.getUnit();
		}		
	}
	public void write(byte b[], int off, int length) throws CachePoolFullException, InterruptedException {
				
		if (curCU == null) {
			curCU = getUnit(); 
			content.add(curCU);
		}		 
		int m = length;
		while (m > 0) {
			int res = curCU.write(b, off + length - m, m);
			if (res < m) {
				curCU = getUnit();
				content.add(curCU);
			}
			len += res;
			m -= res;
		}			
	}	
	
	public int write(InputStream in) throws IOException {
		if (curCU == null) {
			curCU = getUnit();
			content.add(curCU);
		}	
		int res = 0;
		int total = 0;
		while(true) {
			res = curCU.write(in);
			if (res == 0) {				
				curCU = getUnit();
				content.add(curCU);
				continue;
			} else if (res < 0) {
					break;				
			}
			len += res;			
			total += res;			
		}		
		LOG.info("len: " + len);
		if (total == 0 && res < 0) {
			return res;
		}
		return total;
	}
	public void write(int b) throws IOException, InterruptedException {
		if (curCU == null) {
			curCU = getUnit();
			content.add(curCU);
		}		
		if (!curCU.write(b)) {
			curCU = getUnit();
			content.add(curCU);						
			if (curCU.write(b)) {
				len++;
			} else {
				throw new IOException("CacheUnit cap = 0"); 
			}			
		} else {
			len++;
		}
  }
	public void setDelelte(boolean d) {
		delete = d;
	}
	@Override
	public int read() throws IOException {
		// TODO Auto-generated method stub
		if (cursor >= len) {
			return -1;
		} else {
			int res = content.get((int)(cursor/CacheUnit.cap)).read();
			cursor++;
			return res;
		}				
	}
	
	public int read(byte b[], int off, int length) {
		if (b == null) {
	    throw new NullPointerException();
		} else if (off < 0 || length < 0 || length > b.length - off) {
	    throw new IndexOutOfBoundsException();
		}
		if (cursor >= len) {
		    return -1;
		}
		if (cursor + length > len) {
			length = (int)(len - cursor);
		}
		if (length <= 0) {
		    return 0;
		}
		int m = length;
		while(m > 0 && cursor < len) {
			CacheUnit cu = content.get((int)(cursor/CacheUnit.cap));
			int tm = Math.min(cu.available(), m);
			cu.read(b, off + (length - m), tm);
			m -= tm;
			cursor += tm;
		}				
		return length - m;		
	}
	public long skip(long n) {		
		if (delete) {   //delete model don't support skip
			return 0;
		}
		if (cursor + n > len) {
	    n = len - cursor;
		}
		if (n < 0) {
		    return 0;
		}
		long m = n;
		while (m > 0 && cursor < len) {
			CacheUnit cu = content.get((int)(cursor/CacheUnit.cap));
			long tm = Math.min(cu.available(), m);
			cu.skip(tm);
			m -= tm;
			cursor += tm;
		}		
	//	LOG.info("skip content size: " + content.size() + ", len: " + len 
	//			+ ", cursor: " + cursor + ", n: " + n + ", m: " + m);
		return n - m;
	}
	public void close() {       //for read
		
	}
	public void writeFile(FSDataOutputStream out) throws IOException {
		for(CacheUnit cu : content) {
			cu.writeFile(out);
		}
		out.close();
	}
	public void reset() {
		cursor = 0;
		for(CacheUnit cu : content) {
			cu.reset();
		}
 	}
	public void clear() {       
		pool.returnUnit(content);
		pool.returnUnit(rcontent);
		content.clear();
		rcontent.clear();
		len = 0;
		cursor = 0;
	}
	public boolean canRead() {
		return isWriteDone;
	}
	public void closeWrite() throws IOException, InterruptedException {		
		if ((len == 0 && curCU == null)) {
			isWriteDone = true;
		}
		if (isWriteDone) {
			return;
		}
		isWriteDone = true;
		curCU.setLast();	
	}
	public long getLen() {		
		return len;
	}	
	public long getPos() {		
		return cursor;
	}
	public long getCap() {
		return rlen <= 0 ? (long)content.size() * (long)CacheUnit.cap : rlen;
	}
	public static long size2Cap(long size) {
		return size % CacheUnit.cap == 0 ? 
				size : (size / CacheUnit.cap + 1) * CacheUnit.cap;
	}
}
