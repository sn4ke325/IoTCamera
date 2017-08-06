package it.unibo.studio.iot.scc;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.video.BackgroundSubtractorMOG2;
import org.opencv.video.Video;
import org.opencv.videoio.VideoCapture;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;


public class Main {

	public static void main(String[] args) {
		// We can use args to fill deviceId and groupId
		String deviceId = "1";
		String groupId = "testing";

		// loading OpenCV libraries
		System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
		/*System.out.println("test mog2 in main");
		VideoCapture cap = new VideoCapture();
		BackgroundSubtractorMOG2 mog2 = Video.createBackgroundSubtractorMOG2();
		cap.open(0);
		Mat frame = new Mat();
		if (cap.isOpened()) {
			cap.read(frame);
			Mat fgmask = new Mat();
			mog2.apply(frame, fgmask);
		}
		cap.release();
		System.out.println("mog2 in main tested");
*/
		// initiating actor system and top level supervisor
		ActorSystem system = ActorSystem.create("iot-camera-system");
		ActorRef camera = system.actorOf(IotCameraSupervisor.props(deviceId, groupId), "iot-camera-supervisor");

	}

}
