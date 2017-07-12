package it.unibo.studio.iot.scc;

import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;

import akka.NotUsed;
import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.dispatch.ExecutionContexts;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.stream.ActorMaterializer;
import akka.stream.Attributes;
import akka.stream.Materializer;
import akka.stream.Outlet;
import akka.stream.OverflowStrategy;
import akka.stream.SourceShape;
import akka.stream.ThrottleMode;
import akka.stream.impl.ActorPublisher;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.SourceQueueWithComplete;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import scala.concurrent.duration.FiniteDuration;

import it.unibo.studio.iot.scc.messages.*;

public class IotCameraVASupervisor extends AbstractActor {

	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

	private boolean cameraActive;
	private VideoCapture capture;
	private int cameraId;
	private ScheduledExecutorService timer;
	private Mat frame;

	// streamlining
	private final Materializer materializer = ActorMaterializer.create(this.getContext());
	private Source<Mat, NotUsed> frameSource;
	//private Sink<Mat,CompletionStage<Mat>> frameSink;

	// for debug purposes

	private JFrame window;
	private JLabel lbl;

	public IotCameraVASupervisor(int cameraId) {
		this.cameraId = cameraId;
	}

	public static Props props(int cameraId) {
		return Props.create(IotCameraVASupervisor.class, cameraId);
	}

	public void preStart() {
		this.cameraActive = false;
		this.capture = new VideoCapture();

		// streamlining
		// creating an Akka stream Source that pushes frames according to back
		// pressure
		this.frameSource = Source.fromGraph(new CameraFrameSource(capture));
		// creating an Akka stream Sink to obtain a frame
this.frameSink =		
		// for debug purposes we create a window to watch the video
		this.window = new JFrame();
		this.window.setLayout(new FlowLayout());
		this.window.setSize(1280, 720);
		this.lbl = new JLabel();
		this.window.add(lbl);
		this.window.setVisible(true);
		this.window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
	}

	private void startVideoCapture() {
		this.capture.open(cameraId);
		if (capture.isOpened()) {
			this.cameraActive = true;

			this.frameSource.throttle(33, FiniteDuration.create(1, TimeUnit.SECONDS), 1, ThrottleMode.shaping())
			
			/*Runnable framegrabber = new Runnable() {

				public void run() {
					Mat frame = grabFrame();
					// System.out.println("grabframe");
					showFrame(frame);
				}

			};

			this.timer = Executors.newSingleThreadScheduledExecutor();
			this.timer.scheduleAtFixedRate(framegrabber, 0, 33, TimeUnit.MILLISECONDS);*/

		} else {
			System.err.println("Can't open camera connection.");
		}
	}

	private void showFrame(Mat frame) {

		lbl.setIcon(new ImageIcon(MatToBufferedImage(frame)));
		window.repaint();

	}

	private BufferedImage MatToBufferedImage(Mat frame) {

		int type = 0;
		if (frame.channels() == 1)
			type = BufferedImage.TYPE_BYTE_GRAY;
		else if (frame.channels() == 3)
			type = BufferedImage.TYPE_3BYTE_BGR;
		BufferedImage image = new BufferedImage(frame.width(), frame.height(), type);
		WritableRaster raster = image.getRaster();
		DataBufferByte dataBuffer = (DataBufferByte) raster.getDataBuffer();
		byte[] data = dataBuffer.getData();
		frame.get(0, 0, data);
		return image;

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

// creating a source that grabs frame from camera

class CameraFrameSource extends GraphStage<SourceShape<Mat>> {

	public final Outlet<Mat> out = Outlet.create("CameraFrameSource.out");
	private final SourceShape<Mat> shape = SourceShape.of(out);
	private VideoCapture capture;

	public CameraFrameSource(VideoCapture cap) {
		this.capture = cap;
	}

	@Override
	public SourceShape<Mat> shape() {
		return shape;
	}

	@Override
	public GraphStageLogic createLogic(Attributes inheritedAttributes) throws Exception {
		return new GraphStageLogic(shape) {
			{
				setHandler(out, new AbstractOutHandler() {

					@Override
					public void onPull() throws Exception {
						Mat frame = new Mat();
						if (capture.isOpened()) {
							try {
								capture.read(frame);
							} catch (Exception e) {
								System.err.println("Exception during the image elaboration: " + e);
							}
						}
						push(out, frame);

					}

				});
			}
		};
	}

}
