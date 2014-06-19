package org.red5.demos.rtsp;

import org.red5.server.api.event.IEvent;

public interface IEventSink {
	
	public void dispatchEvent(IEvent event);

}
