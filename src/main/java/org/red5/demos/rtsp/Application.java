package org.red5.demos.rtsp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.red5.proxy.StreamingProxy;
import org.red5.server.adapter.MultiThreadedApplicationAdapter;
import org.red5.server.api.IContext;
import org.red5.server.api.Red5;
import org.red5.server.api.scope.IBroadcastScope;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.api.stream.IStreamListener;
import org.red5.server.api.stream.IStreamPacket;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.scope.BroadcastScope;
import org.red5.server.stream.IProviderService;
import org.red5.server.stream.message.RTMPMessage;

public class Application extends MultiThreadedApplicationAdapter implements IStreamListener {

	ICYStream output;

	AxisTest input;

	StreamingProxy streamer;

	int limiter = 1024;

	public boolean appStart(IScope scope) {

		super.appStart(scope);

		output = new ICYStream("axis");

		output.setScope(scope);

		input = new AxisTest(output);

		output.setVideoFramer(input);

		output.addStreamListener(this);
		streamer = new StreamingProxy();
		streamer.init();
		streamer.setApp("bwservice");
		streamer.setHost("192.168.10.2");
		streamer.setPort(1935);
		streamer.start("axisrecord" + System.currentTimeMillis() / 1000, "record", new Object[] {});

		IContext context = scope.getContext();

		IProviderService providerService = (IProviderService) context.getBean(IProviderService.BEAN_NAME);

		if (providerService.registerBroadcastStream(scope, output.getPublishedName(), output)) {
			IBroadcastScope bsScope = (BroadcastScope) providerService.getLiveProviderInput(scope, output.getPublishedName(), true);
		}

		Thread runner = new Thread(input);

		runner.start();

		return true;
	}

	public List<String> getRooms() {
		List<String> results = new ArrayList<String>(scope.getScopeNames());
		return results;
	}

	public Set<String> getStreams() {
		return getBroadcastStreamNames(Red5.getConnectionLocal().getScope());
	}

	public synchronized void checkBW(int seconds, int chunkSize) {
		BandwidthChecker checker = new BandwidthChecker(Red5.getConnectionLocal(), seconds, chunkSize);
		this.addScheduledOnceJob(1000, checker);

	}

	@Override
	public void packetReceived(IBroadcastStream stream, IStreamPacket packet) {

		RTMPMessage m = RTMPMessage.build((IRTMPEvent) packet, packet.getTimestamp());

		try {

			limiter--;
			if (limiter > 1) {
				streamer.pushMessage(null, m);
			} else {
				if (streamer != null) {
					stream.removeStreamListener(this);
					streamer.stop();
					streamer = null;
				}
			}

		} catch (IOException e) {

			e.printStackTrace();
		}

	}

}
