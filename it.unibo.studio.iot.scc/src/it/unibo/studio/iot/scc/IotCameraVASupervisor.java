package it.unibo.studio.iot.scc;

import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.BackgroundSubtractorMOG2;
import org.opencv.video.Video;
import org.opencv.videoio.VideoCapture;

import akka.NotUsed;
import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.stream.ActorMaterializer;
import akka.stream.Attributes;
import akka.stream.FanInShape2;
import akka.stream.FlowShape;
import akka.stream.Graph;
import akka.stream.Inlet;
import akka.stream.KillSwitches;
import akka.stream.Materializer;
import akka.stream.Outlet;
import akka.stream.SourceShape;
import akka.stream.ThrottleMode;
import akka.stream.UniformFanOutShape;
import akka.stream.UniqueKillSwitch;
import akka.stream.javadsl.Broadcast;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.GraphDSL;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.RunnableGraph;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.ZipWith;
import akka.stream.stage.AbstractInHandler;
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

	// stream
	private final Materializer materializer = ActorMaterializer.create(this.getContext());
	private Source<Mat, NotUsed> frameSource;
	private Graph<FlowShape<Mat, Pair<Mat, Mat>>, NotUsed> videoAnalysisPartialGraph;
	// private Sink<Mat,CompletionStage<Mat>> frameSink;
	private RunnableGraph<UniqueKillSwitch> stream;
	private UniqueKillSwitch killswitch;

	// tools for video analysis
	private BackgroundSubtractorMOG2 mog2;

	// parameters for image processing
	private int threshold_value;
	private int frame_history_length;
	private int erosion_size;
	private int dilation_size;
	private int blur_size;

	// for debug purposes we create a window
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
		this.frame_history_length = 100;
		this.mog2 = Video.createBackgroundSubtractorMOG2();
		this.mog2.setDetectShadows(false);
		this.threshold_value = 200;
		this.dilation_size = 5;
		this.erosion_size = 5;
		this.blur_size = 5;

		// stream
		// Akka stream Source that pushes frames according to back
		// pressure
		this.frameSource = Source.fromGraph(new CameraFrameSource(capture));

		// partial akka stream graph that does the video analysis
		this.videoAnalysisPartialGraph = GraphDSL.create(builder -> {
			final UniformFanOutShape<Mat, Mat> A = builder.add(Broadcast.create(2));
			/*
			 * final FlowShape<Mat, Mat> mask =
			 * builder.add(Flow.of(Mat.class).map(f -> { return f; }));
			 */

			final FlowShape<Mat, Mat> bgs = builder.add(Flow.of(Mat.class).map(f -> {
				return subtractBackground(f);
			}).async());

			final FlowShape<Mat, Mat> imgproc = builder.add(Flow.of(Mat.class).map(src -> {
				Mat temp = new Mat();
				Mat dst = new Mat();
				Imgproc.threshold(src, temp, threshold_value, 255, Imgproc.THRESH_BINARY);
				Imgproc.blur(temp, dst, new Size(2 * blur_size + 1, 2 * blur_size + 1));
				Mat elementD = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
						new Size(2 * dilation_size + 1, 2 * dilation_size + 1));
				Imgproc.dilate(dst, temp, elementD);
				Mat elementE = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
						new Size(2 * erosion_size + 1, 2 * erosion_size + 1));
				Imgproc.erode(temp, dst, elementE);
				Imgproc.dilate(dst, temp, elementD);
				Imgproc.GaussianBlur(temp, dst, new Size(2 * blur_size + 1, 2 * blur_size + 1), 2 * blur_size);
				Imgproc.threshold(dst, temp, 50, 255, Imgproc.THRESH_BINARY);
				Imgproc.erode(temp, dst, elementE);

				return dst;
			}));

			final FanInShape2<Mat, Mat, Pair<Mat, Mat>> zip = builder.add(ZipWith.create((Mat left, Mat right) -> {
				return new Pair<Mat, Mat>(left, right);
			}));

			builder.from(A).toInlet(zip.in1());
			builder.from(A).via(bgs).via(imgproc).toInlet(zip.in0());

			return new FlowShape<Mat, Pair<Mat, Mat>>(A.in(), zip.out());

		});

		this.stream = frameSource.throttle(33, FiniteDuration.create(1, TimeUnit.SECONDS), 1, ThrottleMode.shaping())
				.via(this.videoAnalysisPartialGraph).map(p -> p.first()).viaMat(KillSwitches.single(), Keep.right())
				.toMat(Sink.foreach(f -> showFrame(f)), Keep.left());

		// for debug purposes we create a window to watch the video

		this.window = new JFrame();
		this.window.setLayout(new FlowLayout());
		this.window.setSize(1280, 720);
		this.lbl = new JLabel();
		this.window.add(lbl);
		this.window.setVisible(true);
		this.window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

		// test
		/*
		 * new Thread(() -> { this.capture = new VideoCapture(); this.mog2 =
		 * Video.createBackgroundSubtractorMOG2();
		 * 
		 * this.capture.open(cameraId); if (capture.isOpened()) {
		 * System.out.println("Capture is opened"); // Mat i = new Mat(100,100,
		 * CvType.CV_8SC3 , new Scalar(0,0,0)); // //crea una immagine nera Mat
		 * i = new Mat(); Mat j = new Mat(); capture.read(i); new Thread(() -> {
		 * System.out.println("Before mog2"); mog2.apply(i, j); showFrame(j);
		 * }).start(); // this.subtractBackground(i);
		 * 
		 * } this.capture.release(); }).start();
		 * 
		 * // end test
		 * 
		 */
	}

	private void startVideoCapture() {
		// this.capture.open(cameraId);
		this.capture.open("res/videoplayback.mp4");
		if (capture.isOpened()) {
			this.cameraActive = true;
			killswitch = this.stream.run(materializer);

		} else {
			System.err.println("Can't open camera connection.");
		}
	}

	private void stopVideoCapture() {

		if (this.capture.isOpened()) {
			this.killswitch.shutdown();
			// release the camera
			this.capture.release();
			this.cameraActive = false;

		}

	}

	private Mat mask(Mat frame) {
		// todo

		return frame;
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

	private Mat subtractBackground(Mat frame) {

		Mat fgmask = new Mat();
		mog2.apply(frame, fgmask);
		return fgmask;
	}

	// not needed
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
			private Mat frame;
			{
				setHandler(out, new AbstractOutHandler() {

					@Override
					public void onPull() throws Exception {
						frame = new Mat();
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

// creating a flow that applies MOG2 to a frame and returns the foreground mask
// as a new frame

class MOG2Flow extends GraphStage<FlowShape<Mat, Mat>> {

	public final Inlet<Mat> in = Inlet.create("MOG2Flow.in");
	public final Outlet<Mat> out = Outlet.create("MOG2Flow.out");
	private final FlowShape<Mat, Mat> shape = FlowShape.of(in, out);
	private BackgroundSubtractorMOG2 mog2;

	public MOG2Flow() {
		mog2 = Video.createBackgroundSubtractorMOG2();
		mog2.setDetectShadows(false);
	}

	@Override
	public FlowShape<Mat, Mat> shape() {

		return shape;
	}

	@Override
	public GraphStageLogic createLogic(Attributes inheritedAttributes) throws Exception {

		return new GraphStageLogic(shape) {

			{
				setHandler(in, new AbstractInHandler() {
					public void onPush() {
						Mat frame = grab(in);
						Mat fgmask = new Mat();
						mog2.apply(frame, fgmask);
						push(out, fgmask);
					}
				});
				setHandler(out, new AbstractOutHandler() {
					public void onPull() throws Exception {
						pull(in);
					}
				});
			}

		};
	}

}

class Pair<T1, T2> {

	private T1 A;
	private T2 B;

	public Pair(T1 f, T2 s) {
		this.A = f;
		this.B = s;

	}

	public T1 first() {
		return A;
	}

	public T2 second() {
		return B;
	}

}

// Creating a graph that does the Video analysis and returns frames of the
// captured image with rectangles around tracked people
