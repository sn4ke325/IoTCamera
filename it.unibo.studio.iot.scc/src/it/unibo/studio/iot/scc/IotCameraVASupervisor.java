package it.unibo.studio.iot.scc;

import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.BackgroundSubtractorMOG2;
import org.opencv.video.Video;
import org.opencv.videoio.VideoCapture;

import akka.NotUsed;
import akka.actor.AbstractActor;
import akka.actor.ActorRef;
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
	private Mat frame;
	private boolean video_debug;
	private ActorRef tracker;

	// stream
	private final Materializer materializer = ActorMaterializer.create(this.getContext());
	private Source<Mat, NotUsed> frameSource;
	private Graph<FlowShape<Mat, Mat>, NotUsed> videoAnalysisPartialGraph;
	// private Sink<Mat,CompletionStage<Mat>> frameSink;
	private RunnableGraph<UniqueKillSwitch> stream;
	private UniqueKillSwitch killswitch;

	// tools for video analysis
	private BackgroundSubtractorMOG2 mog2;

	// parameters for image processing
	private Rect roi_rectangle;
	private boolean usemask;
	private int threshold_value;
	private int frame_history_length;
	private int frame_history_passed;
	private int erosion_size;
	private int dilation_size;
	private int blur_size;

	// blob weight and filtering
	private int minimum_blob_width; // boundingbox
	private int minimum_blob_height; // boundingbox
	private double minimum_blob_area; // contour

	// In and out counting area should be submatrixes of roi
	private Rect in_zone, out_zone, crossing_zone;
	// crossing line approach
	private boolean vertical, flip_scene; // flags to understand the orientation
											// of in/out and scene
	private double crossing_line; // coord of vertical or horizontal line to
									// split the scene

	// for debug purposes we would like to create a window
	private JFrame window;
	private JLabel lbl;
	private JLabel lbl1;

	public IotCameraVASupervisor(int cameraId) {
		this.cameraId = cameraId;
	}

	public static Props props(int cameraId) {
		return Props.create(IotCameraVASupervisor.class, cameraId);
	}

	public void preStart() {
		this.cameraActive = false;
		this.capture = new VideoCapture();
		// default parameters
		this.usemask = true;
		this.roi_rectangle = new Rect(0, 100, 480, 180);
		this.frame_history_length = 30;
		this.mog2 = Video.createBackgroundSubtractorMOG2();
		this.mog2.setDetectShadows(false);
		this.mog2.setHistory(frame_history_length);
		this.threshold_value = 200;
		this.dilation_size = 12;
		this.erosion_size = 5;
		this.blur_size = 5;
		this.minimum_blob_area = 20;
		this.minimum_blob_height = 80;
		this.minimum_blob_width = 80;
		this.in_zone = new Rect(roi_rectangle.x, roi_rectangle.y, 30, roi_rectangle.height);
		this.out_zone = new Rect(roi_rectangle.x + roi_rectangle.width - 30, roi_rectangle.y, 30, roi_rectangle.height);
		this.crossing_line = 250;
		this.vertical = true;
		this.flip_scene = false;

		// stream
		// Akka stream Source that pushes frames according to back
		// pressure
		this.frameSource = Source.fromGraph(new CameraFrameSource(capture));

		// partial akka stream graph that does the video analysis
		this.videoAnalysisPartialGraph = GraphDSL.create(builder -> {
			final UniformFanOutShape<Mat, Mat> A = builder.add(Broadcast.create(2));

			final FlowShape<Mat, Mat> mask = builder.add(Flow.of(Mat.class).map(f -> {
				if (usemask) {

					/*
					 * lbl1.setIcon(new ImageIcon(MatToBufferedImage(output)));
					 * window.repaint();
					 */
					return mask(f, roi_rectangle);

				}
				return f;
			}));

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

			final FlowShape<Mat, List<MatOfPoint>> find_contours = builder.add(Flow.of(Mat.class).map(src -> {
				List<MatOfPoint> contours = new ArrayList<MatOfPoint>();
				if (!(frame_history_passed < frame_history_length)) {
					Mat hierarchy = new Mat();
					Imgproc.findContours(src, contours, hierarchy, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE);
				}
				return contours;
			}));

			final FlowShape<List<MatOfPoint>, List<Blob>> find_blobs = builder.add(Flow.fromFunction(src -> {
				List<Blob> blobs = new ArrayList<Blob>();
				for (int i = 0; i < src.size(); i++) {
					Blob b = new Blob(src.get(i));
					// filter blobs that are too small
					if (!(b.getBoundingBox().width < minimum_blob_width
							|| b.getBoundingBox().height < minimum_blob_height)) {

						// set blob weight by area TODO
						b.setWeight(1);
						blobs.add(b);
					}
				}

				tracker.tell(new UpdateTracking(blobs), this.getSelf());

				return blobs;
			}));

			/*
			 * final FanInShape2<List<MatOfPoint>, Mat, Mat> zip = builder
			 * .add(ZipWith.create((List<MatOfPoint> left, Mat right) -> { int
			 * idx = 0; Imgproc.drawContours(right, left, idx++, new Scalar(255,
			 * 0, 0)); // draws unmasked area Imgproc.rectangle(right, new
			 * Point(roi_rectangle.x, roi_rectangle.y), new
			 * Point(roi_rectangle.x + roi_rectangle.width, roi_rectangle.y +
			 * roi_rectangle.height), new Scalar(0, 255, 0));
			 * 
			 * return right; }));
			 */

			final FanInShape2<List<Blob>, Mat, Mat> zip_draw = builder
					.add(ZipWith.create((List<Blob> left, Mat right) -> {
						for (Blob b : left) {
							Rect r = b.getBoundingBox();
							Imgproc.rectangle(right, new Point(r.x, r.y), new Point(r.x + r.width, r.y + r.height),
									new Scalar(255, 0, 0));
							Imgproc.circle(right, b.getCentroid(), 2, new Scalar(255, 0, 0));
						}
						// draws unmasked area
						Imgproc.rectangle(right, new Point(roi_rectangle.x, roi_rectangle.y),
								new Point(roi_rectangle.x + roi_rectangle.width,
										roi_rectangle.y + roi_rectangle.height),
								new Scalar(255, 255, 0));
						// draw areas
						/*
						 * Imgproc.rectangle(right, new Point(in_zone.x,
						 * in_zone.y), new Point(in_zone.x + in_zone.width,
						 * in_zone.y + in_zone.height), new Scalar(0, 255, 0));
						 * Imgproc.rectangle(right, new Point(out_zone.x,
						 * out_zone.y), new Point(out_zone.x + out_zone.width,
						 * out_zone.y + out_zone.height), new Scalar(0, 0,
						 * 255));
						 */
						// draw crossing line
						if (vertical)
							Imgproc.line(right, new Point(crossing_line, 0), new Point(crossing_line, right.height()),
									new Scalar(0, 0, 255));
						else
							Imgproc.line(right, new Point(0, crossing_line), new Point(right.width(), crossing_line),
									new Scalar(0, 0, 255));
						return right;
					}));

			builder.from(A).toInlet(zip_draw.in1());
			builder.from(A).via(mask).via(bgs).via(imgproc).via(find_contours).via(find_blobs).toInlet(zip_draw.in0());

			return new FlowShape<Mat, Mat>(A.in(), zip_draw.out());

		});

		this.stream = frameSource.throttle(33, FiniteDuration.create(1, TimeUnit.SECONDS), 1, ThrottleMode.shaping())
				.via(this.videoAnalysisPartialGraph).viaMat(KillSwitches.single(), Keep.right())
				.toMat(Sink.foreach(f -> showFrame(f, lbl)), Keep.left());

		// for debug purposes we create a window to watch the video
		/*
		 * this.window = new JFrame(); this.window.setLayout(new FlowLayout());
		 * this.window.setSize(1280, 720); this.lbl = new JLabel(); this.lbl1 =
		 * new JLabel(); this.window.add(lbl); this.window.add(lbl1);
		 * this.window.setVisible(true);
		 * this.window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		 */

	}

	private void startVideoCapture() {

		if (this.video_debug) {
			this.window = new JFrame();
			this.window.setLayout(new FlowLayout());
			this.window.setSize(1280, 720);
			this.lbl = new JLabel();
			this.lbl1 = new JLabel();
			this.window.add(lbl);
			this.window.add(lbl1);
			this.window.setVisible(true);
			this.window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		}

		this.tracker = this.getContext().actorOf(TrackerActor.props(crossing_line, vertical, flip_scene), "Tracker");

		// this.capture.open(cameraId);
		this.capture.open("res/videoplayback.mp4");
		this.frame_history_passed = 0;
		if (capture.isOpened()) {
			this.cameraActive = true;
			killswitch = this.stream.run(materializer);

		} else {
			System.err.println("Can't open camera connection.");
		}
	}

	private void stopVideoCapture() {

		if (this.video_debug) {
			this.window.dispose();
		}
		if (this.capture.isOpened()) {
			this.killswitch.shutdown();
			// release the camera
			this.capture.release();
			this.cameraActive = false;

		}
		this.getContext().stop(tracker);

	}

	private Mat mask(Mat f, Rect roi) {
		Mat zeromask = Mat.zeros(f.size(), CvType.CV_8U);
		zeromask.submat(roi).setTo(new Scalar(255));
		Mat output = new Mat(f.size(), f.type());
		f.copyTo(output, zeromask);
		return output;
	}

	private void showFrame(Mat frame, JLabel label) {

		label.setIcon(new ImageIcon(MatToBufferedImage(frame)));
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
		if (frame_history_passed < frame_history_length)
			frame_history_passed++;
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
			this.video_debug = r.withVideo();
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
