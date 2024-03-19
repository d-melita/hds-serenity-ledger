package pt.ulisboa.tecnico.hdsledger.clientLibrary;

import pt.ulisboa.tecnico.hdsledger.utilities.*;
import pt.ulisboa.tecnico.hdsledger.communication.*;
import pt.ulisboa.tecnico.hdsledger.consensus.message.Message;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.*;
import java.util.stream.IntStream;
import java.util.logging.Level;

public class ClientStub {

    // Logger
    private static final CustomLogger LOGGER = new CustomLogger(ClientStub.class.getName());

    // Client identifier (self)
    private final ProcessConfig config;

    // Configs for everyone (replicas and clients)
    ProcessConfig[] others;

    // Link to communicate with nodes
    private final Link link;

    // Map of responses from nodes
    private final Map<Integer, AppendRequest> responses = new HashMap<>(); // TODO - Change AppendRequest to appropriate type of Response
    
    // Current request ID
    private int requestId = 0;

    private final int n;

    private ReceivedSlots receivedSlots;

    public ClientStub(int n, ProcessConfig clientConfig, ProcessConfig[] nodeConfigs, boolean activateLogs) throws HDSSException {
        this.config = clientConfig;
        this.others = nodeConfigs;
        this.n = n;
        this.link = new HMACLink(clientConfig,
						clientConfig.getPort(),
						nodeConfigs,
						LedgerMessage.class);
        this.receivedSlots = new ReceivedSlots(n);
    }

    private AppendMessage createAppendRequestMessage(int id, int receiver, String value, int sequenceNumber) {
        AppendRequest appendRequest = new AppendRequest(value, sequenceNumber);

        AppendMessage message = new AppendMessage(id, Message.Type.APPEND_REQUEST, receiver);

        message.setMessage(new Gson().toJson(appendRequest));
        message.signSelf(this.config.getPrivateKey());

        return message;
    }

    public int append(String value)  throws InterruptedException{
        int currentRequestId = this.requestId++; // nonce
        String key = String.format("%s_%s", value, currentRequestId);
        int thisId = currentRequestId++;
        for (int i = 0; i < n; i++) {
            AppendMessage request = createAppendRequestMessage(config.getId(), i, value, thisId);
            this.link.send(i, request);
        }

        receivedSlots = new ReceivedSlots(n);

        while (!receivedSlots.hasDecided()) {
            try {
                // TODO (dsa): bad
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        Optional<Integer> slotId = receivedSlots.getDecidedSlot();
        
        if (slotId.isPresent()) {
            System.out.println("Slot decided after f+1 confirmations");
        } else {
            throw new RuntimeException("Slot is not present where it should");
        }

        return slotId.get();
    }

    private LedgerMessage createLedgerMessage(int id, Message.Type type, String message) {
        LedgerMessage ledgerMessage = new LedgerMessage(id, type);
        ledgerMessage.setMessage(message);
        ledgerMessage.signSelf(this.config.getPrivateKey());
        return ledgerMessage;
    }

    public int transfer(String sourcePublicKey, String destinationPublicKey, int amount, int tip) {
        int currentRequestId = this.requestId++; // nonce
        TransferRequest transferRequest = new TransferRequest(sourcePublicKey, destinationPublicKey, amount, tip, currentRequestId);
        LedgerMessage request = createLedgerMessage(config.getId(), Message.Type.TRANSFER_REQUEST, new Gson().toJson(transferRequest));
        System.out.println("Sending transfer request: " + new Gson().toJson(request));

        IntStream.range(0, n).forEach(i -> this.link.send(i, request));

        receivedSlots = new ReceivedSlots(n);

        while (!receivedSlots.hasDecided()) {
            try {
                // TODO (dsa): bad
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        Optional<Integer> slotId = receivedSlots.getDecidedSlot();

        if (slotId.isPresent()) {
            System.out.println("Slot decided after f+1 confirmations");
        } else {
            throw new RuntimeException("Slot is not present where it should");
        }

        return slotId.get();
    }

    public void checkBalance(String publicKey) {
        int currentRequestId = this.requestId++; // nonce
        BalanceRequest balanceRequest = new BalanceRequest(publicKey, currentRequestId);
        LedgerMessage request = createLedgerMessage(config.getId(), Message.Type.BALANCE_REQUEST, new Gson().toJson(balanceRequest));
        System.out.println("Sending balance request: " + new Gson().toJson(request));
    }

    public void handleAppendReply(AppendMessage message) {
        System.out.println("Received append reply");
        AppendReply appendReply = message.deserializeAppendReply();

        String key = String.format("%s_%s", appendReply.getValue(), appendReply.getSequenceNumber());
        receivedSlots.addSlot(appendReply.getSlot(), message.getSenderId());
        System.out.println("Response registered");
    }

    public void handleTransferReply(LedgerMessage message) {
        System.out.println("Received Transfer reply");
        TransferReply transferReply = message.deserializeTransferReply();
        receivedSlots.addSlot(transferReply.getSlot(), message.getSenderId());
        System.out.println("Response registered");
    }

    public void handleBalanceReply(LedgerMessage message) {
        System.out.println("Received Balance reply");
        BalanceReply balanceReply = message.deserializeBalanceReply();
        receivedSlots.addSlot(balanceReply.getValue(), message.getSenderId());
        System.out.println("Response registered");
    }

    public void listen() {
        try {
            new Thread(() -> {
                try {
                    while (true) {
                        Message message = link.receive();
                        // Separate thread to handle each message
                        switch (message.getType()) {
                            case APPEND_REPLY -> {
                                AppendMessage reply = (AppendMessage) message;
                                handleAppendReply(reply);
                            }
                            case TRANSFER_REPLY -> {
                                LedgerMessage reply = (LedgerMessage) message;
                                handleTransferReply(reply);
                            }
                            case BALANCE_REPLY -> {
                                LedgerMessage reply = (LedgerMessage) message;
                                handleBalanceReply(reply);
                            }
                            case ACK, IGNORE -> {
                                LOGGER.log(Level.INFO, "Received ACK or IGNORE message. Ignoring.");
                                continue;
                            }
                            default -> {
                                System.out.println(message.getType());
                                throw new HDSSException(ErrorMessage.CannotParseMessage);
                            }
                        }
                    }
                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // class that holds the received slots for each value   
    private static class ReceivedSlots {
        // value -> replica -> slot confirmed
        private final Map<Integer, Integer> slots = new HashMap<>();

        private Optional<Integer> decision = Optional.empty();

        private final int n;

        private final int f;

        public ReceivedSlots(int n) {
            this.n = n;
            this.f = (n-1)/3;
        }

        public synchronized void addSlot(int slotId, int senderId) {
            System.out.printf("Received %d from %d\n", slotId, senderId);
            slots.putIfAbsent(senderId, slotId);
        
            // Histogram
            Map<Integer, Integer> histogram = new HashMap();
            slots.entrySet()
                .stream()
                .map(e -> e.getValue())
                .forEach(slot -> histogram.put(slot, histogram.getOrDefault(slot, 0) + 1));

            for (Map.Entry<Integer, Integer> e: slots.entrySet()) {
                System.out.printf("slots %d: %d\n", e.getKey(), e.getValue());
            }

            for (Map.Entry<Integer, Integer> e: histogram.entrySet()) {
                System.out.printf("Histogram %d: %d\n", e.getKey(), e.getValue());
            }

            Optional<Integer> opt = histogram.entrySet()
                .stream()
                .filter(p -> p.getValue() > this.f)
                .map(p -> p.getKey())
                .findFirst();


            // TODO: change prints to proper logger
            if (opt.isPresent()) {
                decision = Optional.of(opt.get());
            } else {
                System.out.println("No decision yet");
            }
        }


        public synchronized boolean hasDecided() {
            return decision.isPresent();
        }

        public synchronized Optional<Integer> getDecidedSlot() {
            return decision;
        }
    }
}
