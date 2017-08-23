package it.unibo.studio.iot.scc;

import java.util.HashMap;
import java.util.Map;

import org.opencv.core.Rect;

import akka.actor.AbstractActor;
import akka.actor.Props;
import it.unibo.studio.iot.scc.messages.StartVideoCapture;
import it.unibo.studio.iot.scc.messages.StopVideoCapture;
import it.unibo.studio.iot.scc.messages.VideoCaptureStarted;
import it.unibo.studio.iot.scc.messages.VideoCaptureStopped;

public class Tracker extends AbstractActor {

	private Rect in_zone, out_zone;
	private Map<Integer, Blob> alive_blobs; // map that holds the blobs in the
											// scene with their IDs

	public Tracker(Rect in, Rect out) {
		this.in_zone = in;
		this.out_zone = out;

	}

	public void preStart() {
		this.alive_blobs = new HashMap<Integer, Blob>();
	}

	public static Props props(Rect in, Rect out) {
		return Props.create(Tracker.class, in, out);
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder().match(StartVideoCapture.class, r -> {
		}).build();
	}

}
