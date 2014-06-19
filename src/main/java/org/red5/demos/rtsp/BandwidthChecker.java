package org.red5.demos.rtsp;

import java.util.ArrayList;
import java.util.List;

import org.red5.io.utils.ObjectMap;
import org.red5.server.api.IConnection;
import org.red5.server.api.Red5;
import org.red5.server.api.scheduling.IScheduledJob;
import org.red5.server.api.scheduling.ISchedulingService;
import org.red5.server.api.service.IServiceCapableConnection;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.api.stream.IStreamListener;
import org.red5.server.api.stream.IStreamPacket;
import org.red5.server.net.rtmp.event.Notify;

public class BandwidthChecker implements IStreamListener, IScheduledJob {

	private IConnection connection;

	private List<Byte> chunk;

	private long duration;

	private long bytesUp;

	private long deltaUp;

	private long bytesDown;

	private long deltaDown;

	private int chunks = 0;

	private boolean done;

	private long messages;

	public BandwidthChecker(IConnection conn, long forSeconds, long size) {
		connection = conn;
		connection.setAttribute("bwChecker", this);
		duration = forSeconds * 1000;
		chunk = new ArrayList<Byte>();
		int i = (int) size;
		while (i-- > 0)
			chunk.add(new Byte((byte) (i % 255)));

	}

	@Override
	public void execute(ISchedulingService service) throws CloneNotSupportedException {
		run();

	}

	public void run() {

		done = false;

		connection.ping();
		deltaDown = connection.getWrittenBytes();
		deltaUp = connection.getReadBytes();
		endpoint().invoke("onBWStart");

		try {
			Thread.sleep(duration);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		done = true;

		connection.removeAttribute("bwChecker");

		ObjectMap<Object, Object> map = new ObjectMap<Object, Object>();
		map.put("bytesDown", bytesDown);
		map.put("bytesUp", bytesUp);
		map.put("messages", messages);
		map.put("chunks", messages);
		map.put("ping", connection.getLastPingTime());

		endpoint().invoke("onBWDone", new Object[] { map });

	}

	private IServiceCapableConnection endpoint() {
		return (IServiceCapableConnection) connection;
	}

	public void packetReceived(IBroadcastStream arg0, IStreamPacket packet) {

		if (done) {
			arg0.removeStreamListener(this);
			return;
		}

		messages = Red5.getConnectionLocal().getWrittenMessages() + Red5.getConnectionLocal().getReadMessages();

		bytesUp = Red5.getConnectionLocal().getReadBytes();

		bytesDown = Red5.getConnectionLocal().getWrittenBytes();

		if (packet instanceof Notify) {

			endpoint().invoke("onBWChunk", new Object[] { chunk });
			chunks++;
			//  Input reader = new Input(((Notify)packet).getData()); 
			//  reader.readDataType();//string
			//  String  method=reader.readString(null);
			//  reader.readDataType();//object
			//  Map invokeData=  (Map) reader.readMap(new Deserializer(), null);            
			//  System.out.println(method+""+invokeData.get("data").toString());	
		}
	}

}
