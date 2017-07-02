package it.unibo.studio.iot.scc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class IotCameraUISupervisor extends AbstractActor {
	
	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);
	
	public void preStart(){
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
			case "hello":
				output = "hello to you!";
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
		
	return receiveBuilder().matchAny(o -> log.info("received unknown message")).build();
	}

}
