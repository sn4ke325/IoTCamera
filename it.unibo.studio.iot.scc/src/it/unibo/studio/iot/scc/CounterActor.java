package it.unibo.studio.iot.scc;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Timestamp;

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import it.unibo.studio.iot.scc.messages.Count;

public class CounterActor extends AbstractActor {

	private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);
	
	private FileWriter fw;
	private BufferedWriter bw;
	private PrintWriter out;
	private Timestamp timestamp;

	public static Props props() {
		return Props.create(CounterActor.class);
	}

	public void preStart() {
		this.timestamp = new Timestamp(System.currentTimeMillis());
		try {
			this.fw = new FileWriter("countinglog.txt", true);
			this.bw = new BufferedWriter(fw);
			this.out = new PrintWriter(bw);
			this.out.println("Started Logging: " + timestamp);
		} catch (IOException ioe) {
			log.error(ioe.toString());
		}
	}

	public void postStop() {
		out.println("Stopped Logging: " + timestamp);
		try{
	        if( out != null ){
	           out.close(); // Will close bw and fw too
	        }
	        else if( bw != null ){
	           bw.close(); // Will close fw too
	        }
	        else if( fw != null ){
	           fw.close();
	        }
	        else{
	           // Oh boy did it fail hard! :3
	        }
	     }
	     catch( IOException e ){
	        // Closing the file writers failed for some obscure reason
	     }
	}

	@Override
	public Receive createReceive() {
		return receiveBuilder().match(Count.class, r -> {
			if (r.getContent() > 0)
				this.out.println(timestamp + " " + Math.abs(r.getContent()) + " entered.");
			else
				this.out.println(timestamp + " " + Math.abs(r.getContent()) + " left.");
			this.out.flush();
		}).build();
	}

}
