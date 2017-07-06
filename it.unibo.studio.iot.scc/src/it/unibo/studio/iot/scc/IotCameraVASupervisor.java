package it.unibo.studio.iot.scc;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;

import akka.actor.AbstractActor;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.dispatch.ExecutionContexts;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import scala.concurrent.duration.FiniteDuration;

import it.unibo.studio.iot.scc.messages.*;

public class IotCameraVASupervisor extends AbstractActor {

	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

	private boolean cameraActive;
	private VideoCapture capture;
	private int cameraId;
	private ScheduledExecutorService timer;

	public IotCameraVASupervisor(int cameraId) {
		this.cameraId = cameraId;
	}

	public static Props props(int cameraId) {
		return Props.create(IotCameraVASupervisor.class, cameraId);
	}

	public void preStart() {
		this.cameraActive = false;
		this.capture = new VideoCapture();
	}

	private void startVideoCapture() {
		this.capture.open(cameraId);
		if (capture.isOpened()) {
			this.cameraActive = true;

			Runnable framegrabber = new Runnable() {

				public void run() {
					Mat frame = grabFrame();
					// System.out.println("grabframe");

				}
			};

			this.timer = Executors.newSingleThreadScheduledExecutor();
			this.timer.scheduleAtFixedRate(framegrabber, 0, 33, TimeUnit.MILLISECONDS);

		} else {
			System.err.println("Can't open camera connection.");
		}
	}

	private void stopVideoCapture() {
		if (this.timer != null && !this.timer.isShutdown()) {
			try {
				// stop the timer
				this.timer.shutdown();
				this.timer.awaitTermination(33, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				// log any exception
				System.err.println("Exception in stopping the frame capture, trying to release the camera now... " + e);
			}
		}

		if (this.capture.isOpened()) {
			// release the camera
			this.capture.release();
		}
	}

	private Mat grabFrame() {
		Mat frame = new Mat();

		if (this.capture.isOpened()) {
			try {
				capture.read(frame);
			} catch (Exception e) {
				System.err.println("Exception during the image elaboration: " + e);
			}
		}

		return frame;
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder().match(StartVideoCapture.class, r -> {
			log.info("Starting Video Capture and Analysis");
			this.startVideoCapture();
			getSender().tell(new VideoCaptureStarted(), this.getSelf());
		}).match(StopVideoCapture.class, r -> {
			log.info("Stopping Video Capture and Analysis");
			this.stopVideoCapture();
			getSender().tell(new VideoCaptureStopped(), this.getSelf());
		}).build();

	}

}
