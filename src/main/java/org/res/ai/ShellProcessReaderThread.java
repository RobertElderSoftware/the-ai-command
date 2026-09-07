package org.res.ai;

import java.io.InputStream;
import java.util.concurrent.Callable;

public class ShellProcessReaderThread implements Callable<byte []> {
	private String streamName;
	private InputStream inputStream;
	private byte [] bufferedOutput = new byte [0];

	public ShellProcessReaderThread(String streamName, InputStream inputStream){
		this.streamName = streamName;
		this.inputStream = inputStream;
	}

	public byte [] readPartialResult(){
		synchronized (this.bufferedOutput){
			byte [] rtn = this.bufferedOutput.clone();
			this.bufferedOutput = new byte[0];
			return rtn;
		}
	}

	@Override
	public byte [] call() throws Exception{
		try{
			int bufferSize = 4096;
			byte [] buffer = new byte [bufferSize];
			boolean done = false;
			while (!done) {
				int numBytesRead = inputStream.read(buffer, 0, bufferSize);
				if(numBytesRead == -1){
					done = true;
				}else{
					synchronized (this.bufferedOutput){
						byte[] tmp = new byte[this.bufferedOutput.length + numBytesRead];
						System.arraycopy(this.bufferedOutput, 0, tmp, 0, this.bufferedOutput.length);
						System.arraycopy(buffer, 0, tmp, this.bufferedOutput.length, numBytesRead);
						this.bufferedOutput = tmp;
					}
				}
			}
			inputStream.close();	
		}catch (Exception e){
			throw new Exception("Caught an exception in reading input stream name " + this.streamName);
		}
		return this.bufferedOutput;
	}
}
