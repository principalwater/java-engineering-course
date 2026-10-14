package dev.principalwater.store.model;

public enum ImageAsset {
    KEYBOARD("keyboard.svg"), MONITOR("monitor.svg"), LAMP("lamp.svg"),
    NOTEBOOK("notebook.svg"), PLANT("plant.svg"), CUP("cup.svg"),
    HEADPHONES("headphones.svg"), DOCK("dock.svg");

    private final String fileName;

    ImageAsset(String fileName) {
        this.fileName = fileName;
    }

    public String getPath() { return "/images/" + fileName; }
}
