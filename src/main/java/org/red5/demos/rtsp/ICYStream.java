package org.red5.demos.rtsp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.apache.mina.core.buffer.IoBuffer;
import org.red5.codec.StreamCodecInfo;
import org.red5.codec.IStreamCodecInfo;
import org.red5.logging.Red5LoggerFactory;
import org.red5.server.api.event.IEvent;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.statistics.IClientBroadcastStreamStatistics;
import org.red5.server.api.statistics.support.StatisticsCounter;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.api.stream.IStreamListener;
import org.red5.server.api.stream.IStreamPacket;
import org.red5.server.api.stream.ResourceExistException;
import org.red5.server.api.stream.ResourceNotFoundException;
import org.red5.server.messaging.IConsumer;
import org.red5.server.messaging.IMessageComponent;
import org.red5.server.messaging.IPipe;
import org.red5.server.messaging.IPipeConnectionListener;
import org.red5.server.messaging.IProvider;
import org.red5.server.messaging.OOBControlMessage;
import org.red5.server.messaging.PipeConnectionEvent;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.net.rtmp.message.Header;
import org.red5.server.stream.IStreamData;
import org.red5.server.stream.PlayEngine;
import org.red5.server.stream.message.RTMPMessage;
import org.slf4j.Logger;

/**

 * @author Andy Shaules (bowljoman@hotmail.com)
 */
public class ICYStream implements IBroadcastStream, IProvider, IPipeConnectionListener, IEventSink, IClientBroadcastStreamStatistics {

	private static Logger log = Red5LoggerFactory.getLogger(ICYStream.class, "rtsp-restreamer");

	private Set<IStreamListener> mListeners = new CopyOnWriteArraySet<IStreamListener>();

	private String mPublishedName;

	private IPipe mLivePipe;

	private IPipe recordPipe;

	private IScope mScope;

	private IStreamCodecInfo mCodecInfo;

	private List<IConsumer> newComsumers = new ArrayList<IConsumer>();

	private StatisticsCounter subscriberStats = new StatisticsCounter();

	private int streamTime;

	private long bytesReceived = 0;

	private long creationTime;

	private Notify _metaDataEvent;

	private boolean recording;

	private IConsumer recordingFile;

	private AxisTest videoFramer;

	public ICYStream(String name) {
		log.debug("Hello ICYStream!");
		mPublishedName = name;
		mLivePipe = null;
		mCodecInfo = new StreamCodecInfo();
		((StreamCodecInfo)mCodecInfo).setHasAudio(false);
		((StreamCodecInfo)mCodecInfo).setHasVideo(true);
	}

	/**
	 * Implement IStreamListener in an object to monitor the connection status events and auxiliary data.
	 */
	@Override
	public void addStreamListener(IStreamListener listener) {
		log.debug("addStreamListener(listener: {})", listener);
		mListeners.add(listener);
	}

	@Override
	public IProvider getProvider() {
		log.debug("getProvider()");
		return this;
	}

	@Override
	public String getPublishedName() {
		return mPublishedName;
	}

	@Override
	public String getSaveFilename() {
		throw new Error("unimplemented method");
	}

	@Override
	public Collection<IStreamListener> getStreamListeners() {
		return mListeners;
	}

	@Override
	public void removeStreamListener(IStreamListener listener) {
		mListeners.remove(listener);
	}

	@Override
	public void saveAs(String name, boolean append) throws IOException, ResourceNotFoundException, ResourceExistException {

	}

	@Override
	public void setPublishedName(String name) {
		//log.debug("setPublishedName(name:{})", name);
		mPublishedName = name;
	}

	@Override
	public void close() {
		//      log.debug("close()");
	}

	@Override
	public IStreamCodecInfo getCodecInfo() {
		return mCodecInfo;
	}

	@Override
	public String getName() {
		return mPublishedName;
	}

	@Override
	public IScope getScope() {
		return mScope;
	}

	public void setScope(IScope scope) {
		mScope = scope;
	}

	@Override
	public void start() {
		log.debug("start");

		bytesReceived = 0;
		streamTime = 0;
		creationTime = System.currentTimeMillis();

	}

	@Override
	public void stop() {
		log.debug("stop");
		if (recording) {
			recording = false;
			//recordingFilename = null;
			recordPipe.unsubscribe(recordingFile);
			//sendRecordStopNotify();
		}
	}

	@Override
	public void onOOBControlMessage(IMessageComponent arg0, IPipe arg1, OOBControlMessage arg2) {

	}

	@Override
	public void onPipeConnectionEvent(PipeConnectionEvent event) {
		switch (event.getType()) {
			case PipeConnectionEvent.PROVIDER_CONNECT_PUSH:
				if ((event.getProvider() == this) && (event.getParamMap() == null)) {
					mLivePipe = (IPipe) event.getSource();
					log.debug("mLivePipe {}", mLivePipe);
					for (@SuppressWarnings("unused")
					IConsumer consumer : mLivePipe.getConsumers()) {
						subscriberStats.increment();
					}
				}
				break;
			case PipeConnectionEvent.PROVIDER_DISCONNECT:
				if (mLivePipe == event.getSource()) {
					mLivePipe = null;
				}
				break;
			case PipeConnectionEvent.CONSUMER_CONNECT_PUSH:
				if (mLivePipe != null) {
					List<IConsumer> consumers = mLivePipe.getConsumers();
					int count = consumers.size();
					if (count > 0) {
						newComsumers.add(consumers.get(count - 1));
					}
					subscriberStats.increment();
				}
				break;

			case PipeConnectionEvent.CONSUMER_DISCONNECT:
				subscriberStats.decrement();
				break;
			default:
				break;
		}
	}

	private void sendConfig() {

		while (newComsumers.size() > 0) {
			IConsumer consumer = newComsumers.remove(0);
			if (consumer instanceof PlayEngine) {

				streamTime++;
				_metaDataEvent = videoFramer.getMetaDataEvent();
				_metaDataEvent.setTimestamp(streamTime);
				_metaDataEvent.setHeader(new Header());
				_metaDataEvent.getHeader().setTimer(streamTime);
				_metaDataEvent.getHeader().setTimerBase(streamTime);
				RTMPMessage msgM = RTMPMessage.build(_metaDataEvent, streamTime);
				
				try {
					((PlayEngine) consumer).pushMessage(null, msgM);
				} catch (IOException e) {
					log.error("Error icyStream 388 " + e.getMessage());
				}

				int chunk = 0;
				streamTime++;
				VideoData configBody = (VideoData) videoFramer.getAVCDecoderConfig();
				RTMPMessage vidConfig = RTMPMessage.build(configBody, streamTime);
				
				configBody.setTimestamp(streamTime);
				
				try {
					((PlayEngine) consumer).pushMessage(null, vidConfig);

				} catch (IOException e) {
					log.error("Error icyStream 335" + e.getMessage());
				}

				if (videoFramer.getLastKey() != null) {
					streamTime++;
				
					VideoData kmsg = (VideoData) videoFramer.getLastKey();
					RTMPMessage msgK = RTMPMessage.build(kmsg, streamTime);
					chunk += kmsg.getData().limit();
					kmsg.getHeader().setTimer(streamTime);
					kmsg.setTimestamp(streamTime);

					try {

						((PlayEngine) consumer).pushMessage(null, msgK);
					} catch (IOException e) {
						log.error("Error icyStream 352" + e.getMessage());
					}

				}

				List<IRTMPEvent> slices = videoFramer.getLastSlices();

				for (int t = 0; t < slices.size(); t++) {
					streamTime++;
					VideoData slc = (VideoData) slices.get(t);
					chunk += slc.getData().limit();
					slc.setTimestamp(streamTime);
					slc.getHeader().setTimer(streamTime);
					slc.getHeader().setTimerBase(streamTime);
					RTMPMessage msgS = RTMPMessage.build(slc, streamTime);
					
					try {

						((PlayEngine) consumer).pushMessage(null, msgS);
					} catch (IOException e) {
						log.error("Error icyStream 371" + e.getMessage());
					}
				}
				log.debug("New Subscriber key frame video CHUNK SIZE:" + chunk / 1024 + " Kbytes ");
				System.out.println("New Subscriber key frame video CHUNK SIZE:" + chunk + " bytes ");
			}
		}
	}

	public void dispatchEvent(IEvent event) {

		sendConfig();

		if (event instanceof IRTMPEvent) {
			
			IRTMPEvent rtmpEvent = (IRTMPEvent) event;

			log.debug("dispatchEvent" + String.valueOf(rtmpEvent.getTimestamp()));
			((IRTMPEvent) event).setSourceType(Constants.SOURCE_TYPE_LIVE);

			IoBuffer buf = null;

			if (rtmpEvent instanceof IStreamData && (buf = ((IStreamData<?>) rtmpEvent).getData()) != null) {
				bytesReceived += buf.limit();
			}

			streamTime = rtmpEvent.getTimestamp();

			

			if (mLivePipe != null) {
				
				
				
				RTMPMessage msg = RTMPMessage.build(rtmpEvent, streamTime);

				try {
					mLivePipe.pushMessage(msg);
				} catch (Exception e) {
					log.debug("dispatchEvent {}, error: {}", event, e.getMessage());
				}
			}

			// Notify listeners about received packet
			if (rtmpEvent instanceof IStreamPacket) {
				for (IStreamListener listener : getStreamListeners()) {
					try {
						listener.packetReceived(this, (IStreamPacket) rtmpEvent);
					} catch (Exception e) {
						log.error("Error while notifying listener {}, error:{}", listener, e);
					}
				}
			}
		}
	}

	public Notify getMetaData() {
		if (_metaDataEvent != null) {

			return _metaDataEvent;
		}
		return null;
	}

	@Override
	public int getActiveSubscribers() {
		return subscriberStats.getCurrent();
	}

	@Override
	public long getBytesReceived() {
		return bytesReceived;
	}

	@Override
	public int getMaxSubscribers() {
		return subscriberStats.getMax();
	}

	@Override
	public int getTotalSubscribers() {
		return subscriberStats.getTotal();
	}

	@Override
	public int getCurrentTimestamp() {
		return streamTime;
	}

	@Override
	public long getCreationTime() {
		return creationTime;
	}

	public void setMetaDataEvent(Notify event) {

		_metaDataEvent = event;
	}

	public AxisTest getVideoFramer() {
		return videoFramer;
	}

	public void setVideoFramer(AxisTest videoFramer) {
		this.videoFramer = videoFramer;
	}

}
