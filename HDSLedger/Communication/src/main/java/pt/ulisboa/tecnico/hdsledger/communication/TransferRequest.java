package pt.ulisboa.tecnico.hdsledger.communication;

public class TransferRequest {

    private String sourcePublicKey;

    private String destinationPublicKey;

    private int amount;

    private int sequenceNumber;

    public TransferRequest(String sourcePublicKey, String destinationPublicKey, int amount, int sequenceNumber) {
        this.sourcePublicKey = sourcePublicKey;
        this.destinationPublicKey = destinationPublicKey;
        this.amount = amount;
        this.sequenceNumber = sequenceNumber;
    }

    public String getSourcePublicKey() {
        return sourcePublicKey;
    }

    public String getDestinationPublicKey() {
        return destinationPublicKey;
    }

    public int getAmount() {
        return amount;
    }

    public void setSourcePublicKey(String sourcePublicKey) {
        this.sourcePublicKey = sourcePublicKey;
    }

    public void setDestinationPublicKey(String destinationPublicKey) {
        this.destinationPublicKey = destinationPublicKey;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }
}
