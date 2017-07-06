package it.unibo.studio.iot.scc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.Patterns;
import akka.util.Timeout;
import it.unibo.studio.iot.scc.messages.Message;
import it.unibo.studio.iot.scc.messages.StartGUI;
import it.unibo.studio.iot.scc.messages.StartVideoCapture;
import it.unibo.studio.iot.scc.messages.StopVideoCapture;
import it.unibo.studio.iot.scc.messages.VideoCaptureStarted;
import it.unibo.studio.iot.scc.messages.VideoCaptureStopped;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

public class IotCameraUISupervisor extends AbstractActor {

	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

	public void preStart() {
		try {
			this.startConsole();
		} catch (IOException e) {
			// send a message to parent to try and restart this actor
			e.printStackTrace();
		}
	}

	public static Props props() {
		return Props.create(IotCameraUISupervisor.class);
	}

	private void startConsole() throws IOException {
		String input = "";
		String output = "";
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		while (true) {
			System.out.println("Type your command");
			input = br.readLine();
			switch (input) {
			case "start": {
				this.getContext().actorSelection("/user/iot-camera-supervisor/video-analysis-supervisor")
						.tell(new StartVideoCapture(), this.getSelf());
				output = "Sent message to Start Video Capture";
			}
				break;
			case "stop": {
				this.getContext().actorSelection("/user/iot-camera-supervisor/video-analysis-supervisor")
						.tell(new StopVideoCapture(), this.getSelf());
				output = "Sent message to Stop Video Capture";
			}
				break;
			case "gui": {
				this.context().parent().tell(new StartGUI(), this.self());
				output = "User Interface Launched";
			}
				break;
			default:
				output = "No command recognized.";
				break;
			}

			System.out.println(output);

		}
	}

	@Override
	public Receive createReceive() {

		return receiveBuilder().match(Message.class, r -> {
			System.out.println(r.getContent());
		}).match(VideoCaptureStarted.class, r -> {
			log.info("Received: video anaylisis has started.");
		}).match(VideoCaptureStopped.class, r -> {
			log.info("Received: video analysis has stopped/");
		}).build();

	}

}
