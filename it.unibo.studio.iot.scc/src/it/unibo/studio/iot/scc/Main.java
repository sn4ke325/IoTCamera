package it.unibo.studio.iot.scc;

import org.opencv.core.Core;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;

public class Main {

	public static void main(String[] args) {
		//We can use args to fill deviceId and groupId
		String deviceId = "1";
		String groupId = "testing";
		
		//loading OpenCV libraries
		System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
		
		//initiating actor system and top level supervisor
		ActorSystem system = ActorSystem.create("iot-camera-system");		
		ActorRef camera = system.actorOf(IotCameraSupervisor.props(deviceId, groupId), "iot-camera-supervisor");
		
		

	}

}
