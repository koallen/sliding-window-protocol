/*===============================================================*
 *  File: SWP.java                                               *
 *                                                               *
 *  This class implements the sliding window protocol            *
 *  Used by VMach class                                   *
 *  Uses the following classes: SWE, Packet, PFrame, PEvent,     *
 *                                                               *
 *  Author: Professor SUN Chengzheng                             *
 *          School of Computer Engineering                       *
 *          Nanyang Technological University                     *
 *          Singapore 639798                                     *
 *===============================================================*/

import java.util.Timer;
import java.util.TimerTask;

public class SWP {

/*========================================================================*
 the following are provided, do not change them!!
 *========================================================================*/
    // the following are protocol constants.
    public static final int MAX_SEQ = 7;
    public static final int NR_BUFS = (MAX_SEQ + 1)/2;

    // the following are protocol variables
    private int oldest_frame = 0;
    private PEvent event = new PEvent();
    private Packet out_buf[] = new Packet[NR_BUFS];

    // the following are used for simulation purpose only
    private SWE swe = null;
    private String sid = null;

    // Constructor
    public SWP(SWE sw, String s){
        swe = sw;
        sid = s;
    }

    // the following methods are all protocol related
    private void init(){
        for (int i = 0; i < NR_BUFS; i++){
            out_buf[i] = new Packet();
        }
    }

    private void wait_for_event(PEvent e){
        swe.wait_for_event(e); //may be blocked
        oldest_frame = e.seq;  //set timeout frame seq
    }

    private void enable_network_layer(int nr_of_bufs) {
        //network layer is permitted to send if credit is available
        swe.grant_credit(nr_of_bufs);
    }

    private void from_network_layer(Packet p) {
        swe.from_network_layer(p);
    }

    private void to_network_layer(Packet packet) {
        swe.to_network_layer(packet);
    }

    private void to_physical_layer(PFrame fm)  {
        System.out.println("SWP: Sending frame: seq = " + fm.seq +
            " ack = " + fm.ack + " kind = " +
            PFrame.KIND[fm.kind] + " info = " + fm.info.data );
        System.out.flush();
        swe.to_physical_layer(fm);
    }

    private void from_physical_layer(PFrame fm) {
        PFrame fm1 = swe.from_physical_layer();
        fm.kind = fm1.kind;
        fm.seq = fm1.seq;
        fm.ack = fm1.ack;
        fm.info = fm1.info;
    }


/*===========================================================================*
   implement your Protocol Variables and Methods below:
 *==========================================================================*/

    /*******************************************
     * The codes below are added by LIU Siyuan *
     * to fulfill assignment 1 for CZ3006      *
     *******************************************/

    // protocol related variables
    private boolean no_nak = true;
    // timer related variables and constants
    private Timer[] timers = new Timer[NR_BUFS];
    private Timer ack_timer = new Timer();
    private static final int TIMEOUT_INTERVAL = 500;
    private static final int ACK_TIMEOUT_INTERVAL = 300;

    // implementation of the sliding window protocol with selective repeat
    public void protocol6() {
        // protocol initialization
        PFrame inbound_frame = new PFrame();      // incoming frame
        int ack_expected = 0;                     // lower edge of the sender's window
        int next_frame_to_send = 0;               // upper edge of the sender's window
        int frame_expected = 0;                   // lower edge of the receiver's window
        int too_far = NR_BUFS;                    // upper edge of the receiver's window
        Packet in_buf[] = new Packet[NR_BUFS];    // buffer for incoming frames
        boolean arrived[] = new boolean[NR_BUFS]; // bit map for in_buf
        int nbuffered = 0;                        // number of outgoing frames buffered

        enable_network_layer(NR_BUFS);            // allow network layer to send NR_BUFS frames at first
        for (int i = 0; i < NR_BUFS; i++) {
            arrived[i] = false;                   // initially no frame in in_buf
            in_buf[i] = new Packet();
        }
        init();                                   // initialize out_buf

        // start listening to network layer and physical layer
        while(true) {
            wait_for_event(event);
            switch(event.type) {
                // packet available at network layer
                case (PEvent.NETWORK_LAYER_READY):
                    nbuffered++; // expand the window
                    from_network_layer(out_buf[next_frame_to_send % NR_BUFS]);
                    send_frame(PFrame.DATA, next_frame_to_send, frame_expected, out_buf);
                    // advance upper window edge
                    next_frame_to_send = inc(next_frame_to_send);
                    break;
                // frame arrived at physical layer
                case (PEvent.FRAME_ARRIVAL):
                    from_physical_layer(inbound_frame);
                    if (PFrame.KIND[inbound_frame.kind].equals("DATA")) {
                        if ((inbound_frame.seq != frame_expected) && no_nak) {
                            send_frame(PFrame.NAK, 0, frame_expected, out_buf);
                        } else {
                            start_ack_timer();
                        }
                        if (between(frame_expected, inbound_frame.seq, too_far) && (arrived[inbound_frame.seq % NR_BUFS] == false)) {
                            arrived[inbound_frame.seq % NR_BUFS] = true;
                            in_buf[inbound_frame.seq % NR_BUFS] = inbound_frame.info;
                            while (arrived[frame_expected % NR_BUFS]) {
                                to_network_layer(in_buf[frame_expected % NR_BUFS]);
                                no_nak = true;
                                arrived[frame_expected % NR_BUFS] = false;
                                frame_expected = inc(frame_expected);
                                too_far = inc(too_far);
                                start_ack_timer();
                            }
                        }
                    }
                    if (PFrame.KIND[inbound_frame.kind].equals("NAK") && between(ack_expected, (inbound_frame.ack + 1) % (MAX_SEQ + 1), next_frame_to_send)) {
                        send_frame(PFrame.DATA, (inbound_frame.ack + 1) % (MAX_SEQ + 1), frame_expected, out_buf);
                    }
                    while (between(ack_expected, inbound_frame.ack, next_frame_to_send)) {
                        nbuffered--;
                        stop_timer(ack_expected % NR_BUFS);
                        ack_expected = inc(ack_expected);
                        // give credit
                        enable_network_layer(1);
                    }
                    break;
                // arrived frame has checksum error
                case (PEvent.CKSUM_ERR):
                    if (no_nak) {
                        send_frame(PFrame.NAK, 0, frame_expected, out_buf);
                    }
                    break;
                // timer for a certain frame timed out
                case (PEvent.TIMEOUT):
                    send_frame(PFrame.DATA, oldest_frame, frame_expected, out_buf);
                    break;
                // there is no frame to piggyback
                case (PEvent.ACK_TIMEOUT):
                    send_frame(PFrame.ACK, 0, frame_expected, out_buf);
                    break;
                // unknown event
                default:
                    System.out.println("SWP: undefined event type = " + event.type);
                    System.out.flush();
            }
        }
    }

    /* Note: when start_timer() and stop_timer() are called,
     * the "seq" parameter must be the sequence number, rather
     * than the index of the timer array,
     * of the frame associated with this timer,
     */

    private void start_timer(int seq) {
        stop_timer(seq);
        timers[seq % NR_BUFS] = new Timer();
        timers[seq % NR_BUFS].schedule(new FrameTimerTask(seq), TIMEOUT_INTERVAL);
    }

    private void stop_timer(int seq) {
        try {
            timers[seq % NR_BUFS].cancel();
        } catch (Exception e) {}
    }

    private void start_ack_timer() {
        stop_ack_timer();
        ack_timer = new Timer();
        ack_timer.schedule(new TimerTask() {
            @Override
            public void run() {
                swe.generate_acktimeout_event();
            }
        }, ACK_TIMEOUT_INTERVAL);
    }

    private void stop_ack_timer() {
        try {
            ack_timer.cancel();
        } catch (Exception e) {}
    }

    // Helper functions to decompose the logic
    private void send_frame(int frame_kind, int frame_nr, int frame_expected, Packet out_buf[]) {
        // create a new outbound frame
        PFrame outbound_frame = new PFrame();
        // set properties of the outbound frame
        outbound_frame.kind = frame_kind;
        if (PFrame.KIND[frame_kind].equals("DATA")) {
            outbound_frame.info = out_buf[frame_nr % NR_BUFS];
        }
        outbound_frame.seq = frame_nr;
        outbound_frame.ack = (frame_expected + MAX_SEQ) % (MAX_SEQ + 1);
        if (PFrame.KIND[frame_kind].equals("NAK")) {
            no_nak = false;
        }
        to_physical_layer(outbound_frame);
        if (PFrame.KIND[frame_kind].equals("DATA")) {
            start_timer(frame_nr);
        }
        stop_ack_timer();
    }

    // check whether a frame number is within the window
    private boolean between(int a, int b, int c) {
        return ((a <= b) && (b < c)) || ((c < a) && (a <= b)) || ((b < c) && (c < a));
    }

    // circular increment the given number
    private int inc(int number_to_inc) {
        return (number_to_inc + 1) % (MAX_SEQ + 1);
    }

    // Helper class for implementing timer
    private class FrameTimerTask extends TimerTask {
        public int seq; // add an attribute seq to record the sequence number

        public FrameTimerTask(int seq) {
            super();
            this.seq = seq;
        }

        // generate a timeout event of the recorded sequence number when the task is executed
        @Override
        public void run() {
            swe.generate_timeout_event(this.seq);
        }
    }

}

/* Note: In class SWE, the following two public methods are available:
   . generate_acktimeout_event() and
   . generate_timeout_event(seqnr).

   To call these two methods (for implementing timers),
   the "swe" object should be referred as follows:
   . swe.generate_acktimeout_event(), or
   . swe.generate_timeout_event(seqnr).
*/
