/**
 * 
 */
package com.taobao.top.analysis.node.operation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.taobao.top.analysis.config.MasterConfig;
import com.taobao.top.analysis.exception.AnalysisException;
import com.taobao.top.analysis.node.job.Job;
import com.taobao.top.analysis.util.AnalysisConstants;
import com.taobao.top.analysis.util.AnalyzerFilenameFilter;
import com.taobao.top.analysis.util.ReportUtil;

/**
 * 对于某一个Job的数据做操作，支持Job的中间结果导入，导出，删除
 * @author fangweng
 * @Email fangweng@taobao.com
 * 2011-11-29
 *
 */
public class JobDataOperation implements Runnable {

	private final Log logger = LogFactory.getLog(JobDataOperation.class);
	Job job;
	String operation;
	MasterConfig config;
	
	
	public JobDataOperation(Job job,String operation,MasterConfig config)
	{
		this.job = job;
		this.operation = operation;
		this.config = config;
	}

	@Override
	public void run() 
	{
		//先设置主干为空，然后再导出，不影响后续的合并处理，适用于磁盘换内存模式的导出
		if (operation.equals(AnalysisConstants.JOBMANAGER_EVENT_SETNULL_EXPORTDATA))
		{
			if (logger.isInfoEnabled())
				logger.info(job.getJobName() +  " start clear trunk data and exportData now...");
				
			exportData(true);
			
			return;
		}
		
		if(operation.equals(AnalysisConstants.JOBMANAGER_EVENT_EXPORTDATA))
		{
			if (logger.isInfoEnabled())
				logger.info(job.getJobName() +  " start exportData now...");
				
			exportData(false);
			
			return;
		}
		
		if (operation.equals(AnalysisConstants.JOBMANAGER_EVENT_DEL_DATAFILE))
		{
			if (logger.isInfoEnabled())
				logger.info(job.getJobName() +  " delete exportData now...");
				
			deleteData();
			
			return;
		}
		
		if (operation.equals(AnalysisConstants.JOBMANAGER_EVENT_LOADDATA))
		{
			try 
			{
				if (logger.isInfoEnabled())
					logger.info(job.getJobName() +  " start loadData now...");
				
				loadData();
			} 
			catch (AnalysisException e) 
			{
				logger.error(e,e);
			}
			
			return;
		}
			
		if (operation.equals(AnalysisConstants.JOBMANAGER_EVENT_LOADDATA_TO_TMP))
		{
			try 
			{
				if (logger.isInfoEnabled())
					logger.info(job.getJobName() +  " start loadDataToTmp now...");
				
				loadDataToTmp();
			} 
			catch (AnalysisException e) 
			{
				logger.error(e,e);
			}
			
			return;
		}
		
	}
	
	Map<String, Map<String, Object>> innerLoad() throws AnalysisException
	{
		String destDir = getDestDir();	
		
		File dest = new File(destDir);
		if (!dest.exists() || (dest.exists() && !dest.isDirectory()))
			return null;
		
		String _fileSuffix = AnalysisConstants.INNER_DATAFILE_SUFFIX;
		String _bckSuffix = AnalysisConstants.IBCK_DATAFILE_SUFFIX;
		
		
		File[] files = dest.listFiles(new AnalyzerFilenameFilter(_fileSuffix));
		File[] bckfiles = dest.listFiles(new AnalyzerFilenameFilter(_bckSuffix));
		
		if (files.length + bckfiles.length == 0)
			return null;
		
		File[] totalFiles = new File[files.length + bckfiles.length];
		
		if (files.length == 0)
			totalFiles = bckfiles;
		else
		{
			if (bckfiles.length == 0)
				totalFiles = files;
			else
			{
				System.arraycopy(files, 0, totalFiles, 0, files.length);
				System.arraycopy(bckfiles, 0, totalFiles, files.length, bckfiles.length);
			}
		}

		return load(totalFiles);
	}
	
	void deleteData()
	{
		String destDir = getDestDir();	
		
		File dest = new File(destDir);
		if (!dest.exists() || (dest.exists() && !dest.isDirectory()))
			return;
		
		String _fileSuffix = AnalysisConstants.INNER_DATAFILE_SUFFIX;
		String _bckSuffix = AnalysisConstants.IBCK_DATAFILE_SUFFIX;
		
		
		File[] files = dest.listFiles(new AnalyzerFilenameFilter(_fileSuffix));
		File[] bckfiles = dest.listFiles(new AnalyzerFilenameFilter(_bckSuffix));
		
		if (files.length + bckfiles.length == 0)
			return;
		
		File[] totalFiles = new File[files.length + bckfiles.length];
		
		if (files.length == 0)
			totalFiles = bckfiles;
		else
		{
			if (bckfiles.length == 0)
				totalFiles = files;
			else
			{
				System.arraycopy(files, 0, totalFiles, 0, files.length);
				System.arraycopy(bckfiles, 0, totalFiles, files.length, bckfiles.length);
			}
		}
		
		// 当天的备份数据
		Calendar calendar = Calendar.getInstance();
		
		String prefix = new StringBuilder()
				.append(calendar.get(Calendar.YEAR)).append("-")
				.append(String.valueOf(calendar.get(Calendar.MONTH) + 1))
				.append("-").append(calendar.get(Calendar.DAY_OF_MONTH)).append("-")
				.append(job.getJobName())
				.toString();
		
		for (File f : totalFiles) 
		{
			if (!f.getName().startsWith(prefix))
				continue;
			
			f.delete();
		}
	}
	
	void loadDataToTmp() throws AnalysisException
	{
		job.getLoadLock().lock();
		
		try
		{
			Map<String, Map<String, Object>> resultPool = innerLoad();	
			
			if (resultPool == null)
				return;
			
			job.setDiskResult(resultPool);
			
			logger.info("success load data to jobTmpTrunk.");
		}
		finally
		{
			job.getLoadLock().unlock();
		}
	}
	
	void loadData() throws AnalysisException 
	{
		Map<String, Map<String, Object>> resultPool = innerLoad();	
		
		if (resultPool == null)
			return;
		
		
		WriteLock writeLock = job.getTrunkLock().writeLock();
		
		try 
		{
			if (writeLock.tryLock(10, TimeUnit.MINUTES))
			{
				try
				{
					Map<String, Map<String, Object>> jobResult = job.getJobResult();
					if (jobResult != null)
						jobResult.clear();
					jobResult = null;
					
					job.setJobResult(resultPool);
					
					logger.info("success load data to jobTrunk.");
				}
				finally
				{
					writeLock.unlock();
				}
			}
			else
			{
				logger.error("loadData error, can't get writeLock! ");
			}
		} catch (InterruptedException e) {
			//do nothing
		}
				
	}
	
	/**
	 * 私有扁平化载入
	 * @param totalFiles
	 * @return
	 * @throws AnalysisException 
	 */
	private Map<String, Map<String, Object>> load(File[] totalFiles) throws AnalysisException
	{
		boolean error=false;
		
		// 当天的备份数据
		Calendar calendar = Calendar.getInstance();
		String prefix = new StringBuilder()
			.append(calendar.get(Calendar.YEAR)).append("-")
			.append(String.valueOf(calendar.get(Calendar.MONTH) + 1))
			.append("-").append(calendar.get(Calendar.DAY_OF_MONTH)).append("-")
			.append(job.getJobName())
			.toString();
		
		for (File f : totalFiles) 
		{
			if (!f.getName().startsWith(prefix))
				continue;
			
			try
			{
				Map<String, Map<String, Object>> resultPool = this.load(f);
				
				if(resultPool != null && resultPool.size() > 0)
					return resultPool;
			}
			catch(Exception ex)
			{
				logger.error("Load file "+f.getName()+" Error", ex);
				error = true;
			}
		}
		
		if (error)
			throw new AnalysisException("load init data error!");
		else
			return null;
	}
	
	void exportData(boolean setTrunkNull) {
		
		Map<String, Map<String, Object>> resultPool = job.getJobResult();
		
		
		if (resultPool != null && resultPool.size() > 0) {
			
			String destfile=null;
			try 
			{
				String destDir = getDestDir();
						
				File dest = new File(destDir);
				if (!dest.exists() || (dest.exists() && !dest.isDirectory()))
					dest.mkdirs();
				
				Calendar calendar = Calendar.getInstance();

				String dir = new File(destDir).getAbsolutePath();
									
				String _fileSuffix = AnalysisConstants.INNER_DATAFILE_SUFFIX;
				String _bckSuffix = AnalysisConstants.IBCK_DATAFILE_SUFFIX;
									
				if (!dir.endsWith(File.separator))
					dir += File.separator;

				destfile = new StringBuilder(dir)
					.append(calendar.get(Calendar.YEAR))
					.append("-")
					.append(String.valueOf(calendar.get(Calendar.MONTH) + 1))
					.append("-")
					.append(calendar.get(Calendar.DAY_OF_MONTH))
					.append("-")
					.append(job.getJobName())
					.append("-")
					.append(ReportUtil.getIp())
					.append(_fileSuffix).toString();
				
				//做一个备份
				File f = new File(destfile);
				if (f.exists() && f.isFile())
				{
					File bckFile = new File(destfile.replace(_fileSuffix,_bckSuffix));
					if (bckFile.exists())
						bckFile.delete();
						
					f.renameTo(new File(destfile.replace(_fileSuffix,_bckSuffix)));
				}
				
				if (setTrunkNull)
				{
					job.setJobResult(null);
					export(resultPool,destfile);
				}
				else
				{
					ReadLock readLock = job.getTrunkLock().readLock();
					
					try
					{
						if (readLock.tryLock(10, TimeUnit.MINUTES))
						{
							try
							{
								export(resultPool,destfile);
							}
							finally{
								readLock.unlock();
							}
							
						}
						else
						{
							logger.error("exportData error! can't got readLock!");
						}
						
					}
					catch(InterruptedException ie)
					{
						//do nothing
					}
				}
					
			}
			catch (Throwable ex) 
			{
				logger.error(ex.getMessage(), ex);
			} 
		}
	}
	
	public String getDestDir()
	{
		String output = job.getJobConfig().getOutput();
		
		if (output.indexOf(":") > 0)
			output = output.substring(output.indexOf(":")+1);
		
		if (!output.endsWith(File.separator))
			output = output + File.separator;
		
		if (config != null && config.getMasterName() != null)
			return output + config.getMasterName() + File.separator + "tmp" + File.separator;
		else
			return output + "tmp" + File.separator;
	}
	
	/**
	 * 内部协议导入文件生成对象
	 * @param fileName
	 * @return
	 * @throws AnalysisException 
	 */
	public Map<String, Map<String, Object>> load(File file) throws AnalysisException
	{
		BufferedReader breader = null;
		InflaterInputStream inflaterInputStream = null;
		Map<String, Map<String, Object>> resultPool = new HashMap<String,Map<String,Object>>();
		long beg = System.currentTimeMillis();
		
		try
		{
			inflaterInputStream = new InflaterInputStream(
					new FileInputStream(file));
			
			breader = new BufferedReader(new InputStreamReader(inflaterInputStream));
			
			String line;
			while((line = breader.readLine()) != null)
			{
				String[] contents = StringUtils.splitByWholeSeparator(line, AnalysisConstants.EXPORT_RECORD_SPLIT);
				
				Map<String,Object> m = new HashMap<String,Object>();

				resultPool.put(contents[0], m);
				
				for(int i = 1; i < contents.length; i++)
				{
					String[] tt = StringUtils.splitByWholeSeparator(contents[i], AnalysisConstants.EXPORT_COLUMN_SPLIT);
					
					if (tt.length >= 2)
						if (tt.length == 2)
							m.put(tt[0], tt[1]);
						else
							m.put(tt[0], Double.parseDouble(tt[2]));
				}
				
			}
			
			if (logger.isWarnEnabled())
				logger.warn("Load file "+ file.getName() +" Success , use : " + String.valueOf(System.currentTimeMillis() - beg));
			
			return resultPool;
		}
		catch(Exception ex)
		{
			logger.error("Load file "+ file.getName() +" Error", ex);
			throw new AnalysisException("Load file "+ file.getName() +" Error", ex);
		}
		finally
		{
			if (breader != null)
			{
				try {
					breader.close();
				} catch (IOException e) {
					logger.error(e.getMessage(), e);
				}
			}
		}
	}
	
	/**
	 * 内部协议导出对象
	 * @param resultPool
	 * @param FileName
	 */
	public void export(Map<String, Map<String, Object>> resultPool,String FileName)
	{
		BufferedWriter bwriter = null;
		
		try{
			new File(FileName).createNewFile();
			
			Deflater def = new Deflater(Deflater.BEST_SPEED, false);
			
			bwriter = new BufferedWriter(new OutputStreamWriter(new DeflaterOutputStream(
					new FileOutputStream(FileName), def)));
			
			Iterator<String> keys = resultPool.keySet().iterator();
			
			while(keys.hasNext())
			{
				String key = keys.next();
				
				bwriter.write(key);
				bwriter.write(AnalysisConstants.EXPORT_RECORD_SPLIT);
				
				Map<String,Object> m = resultPool.get(key);
							
				Iterator<String> mkeys = m.keySet().iterator();
				
				while(mkeys.hasNext())
				{
					String k = mkeys.next();
					bwriter.write(k);
					bwriter.write(AnalysisConstants.EXPORT_COLUMN_SPLIT);
					
					if (m.get(k) instanceof Double)
						bwriter.write(AnalysisConstants.EXPORT_DOUBLE_SPLIT);
					
					bwriter.write(m.get(k).toString());
					bwriter.write(AnalysisConstants.EXPORT_RECORD_SPLIT);
				}
				
				bwriter.write(ReportUtil.getSeparator());
				
			}
			bwriter.flush();
			
		}
		catch(Exception ex)
		{
			ex.printStackTrace();
		}
		finally {
			if (bwriter != null) {
				try {
					bwriter.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			
		}
	}
	

}
