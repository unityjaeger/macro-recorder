package main;

import input.InputObject;
import gui.MainFrame;
import input.Keyboard;
import input.Mouse;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;

public class Recorder implements EventListener {
    private enum State {
        IDLE,
        RECORDING,
        PLAYING,
        PAUSED
    }

    private static final double EPSILON = 0.005;
    private static final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
    private static final int TARGET_DELAY = 16; // target delay in milliseconds

    public static int RECORD_HOTKEY = NativeKeyEvent.VC_F1;
    public static int PLAYBACK_HOTKEY = NativeKeyEvent.VC_F2;
    public static int PAUSE_HOTKEY = NativeKeyEvent.VC_F3;

    private State currentState = State.IDLE; // Initial state
    private double recordingStartTime;
    private double previousTime;
    private double runTime;

    private ArrayList<InputObject> loggedRecording;
    private int iterator;

    private Mouse mouseListener;
    private Keyboard keyboardListener;
    private MainFrame gui;

    private ScheduledFuture<?> scheduledFuture;

    private boolean continuousPlayback;

    public static void main(String[] args) {
        new Recorder();
    }

    public ArrayList<InputObject> getRecording() {
        return loggedRecording;
    }

    public void setRecording(ArrayList<InputObject> recording) {
        this.loggedRecording = recording;
    }

    public void setContinuousPlayback(boolean enabled) {
        continuousPlayback = enabled;
    }

    public Recorder() {
        loggedRecording = new ArrayList<>();
        continuousPlayback = false;

        try {
            GlobalScreen.registerNativeHook();
        } catch (NativeHookException e) {
            e.printStackTrace();
        }

        // Create listener objects
        gui = new MainFrame(this);
        mouseListener = new Mouse(loggedRecording);
        keyboardListener = new Keyboard(loggedRecording);

        // Add listeners
        gui.addListener(this);
        gui.addListener(keyboardListener);
        gui.addListener(mouseListener);

        GlobalScreen.addNativeKeyListener(keyboardListener);
        GlobalScreen.addNativeKeyListener(gui);
        GlobalScreen.addNativeMouseListener(mouseListener);
        GlobalScreen.addNativeMouseMotionListener(mouseListener);

        // Unregister native hook on close
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                GlobalScreen.unregisterNativeHook();
            } catch (NativeHookException e) {
                e.printStackTrace();
            }
        }));
    }

    private void run() {
        previousTime = System.nanoTime();

        // Main input processing logic for playback
        scheduledFuture = executor.scheduleAtFixedRate(() -> {
            double currentTime = System.nanoTime();
            double deltaTime = (currentTime - previousTime) / 1_000_000_000d;
            runTime += deltaTime;

            update(deltaTime);

            previousTime = currentTime;
        }, TARGET_DELAY, TARGET_DELAY, TimeUnit.MILLISECONDS);
    }

    private void update(double deltaTime) {
        if (iterator >= loggedRecording.size()) {
            if (continuousPlayback) {
                runTime = runTime - loggedRecording.get(loggedRecording.size() - 1).getTimeStamp();
                iterator = 0;
            } else {
                gui.setPlaying(false);
                currentState = State.IDLE;
                return;
            }
        }

        InputObject currentInput = loggedRecording.get(iterator);
        while (currentInput.getTimeStamp() - EPSILON <= runTime) {
            System.out.println("Processed input at " + runTime + " index " + iterator + "/" + (loggedRecording.size() - 1));
            InputSimulator.simulate(currentInput);
            iterator++;
            if (iterator < loggedRecording.size()) {
                currentInput = loggedRecording.get(iterator);
            } else {
                break;
            }
        }
    }

    @Override
    public void onEventTriggered(int type, boolean enabled) {
    	System.out.println(type + " " + currentState);
        switch (currentState) {
            case IDLE:
                if (type == 1 && enabled) { // Start recording
                    loggedRecording.clear();
                    keyboardListener.overWriteRecording(loggedRecording);
                    mouseListener.overWriteRecording(loggedRecording);
                    recordingStartTime = System.nanoTime();
                    currentState = State.RECORDING;
                } else if (type == 2 && enabled) { // Start playback
                    if (loggedRecording.isEmpty()) {
                        gui.setPlaying(false);
                        System.out.println("Playback stopped (empty recording)");
                        return;
                    }
                    currentState = State.PLAYING;
                    runTime = 0;
                    iterator = 0;
                    run();
                    System.out.println("Playback started");
                } else if (type == 3 && enabled) {
                	gui.setPaused(false);
                }
                break;

            case RECORDING:
                if (type == 1 && !enabled) { // Stop recording
                    if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
                        scheduledFuture.cancel(true);
                    }
                    
                    double elapsedTime = (System.nanoTime() - recordingStartTime) / 1_000_000_000d;
                    loggedRecording.add(new InputObject(elapsedTime, (byte) 4));
                    currentState = State.IDLE;
                    System.out.println("Recording stopped, duration: " + elapsedTime);
                } else if (type == 2 && enabled) {
                	gui.setRecording(false);
                	
                	currentState = State.PLAYING;
                	if (loggedRecording.isEmpty()) {
                        gui.setPlaying(false);
                        System.out.println("Playback stopped (empty recording)");
                        return;
                    }
                    currentState = State.PLAYING;
                    runTime = 0;
                    iterator = 0;
                    run();
                    System.out.println("Playback started");
                } else if (type == 3 && enabled) {
                	gui.setPaused(false);
                } 
                break;

            case PLAYING:
            	if (type == 1 && enabled) {
            		gui.setRecording(false);
            	} else if (type == 2 && !enabled) { // Stop playback
                    if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
                        scheduledFuture.cancel(true);
                    }
                    currentState = State.IDLE;
                    System.out.println("Playback stopped");
                } else if (type == 3 && enabled) { // Pause playback
                    if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
                        scheduledFuture.cancel(true);
                    }
                    currentState = State.PAUSED;
                    System.out.println("Playback paused");
                }
                break;

            case PAUSED:
            	if (type == 1 && enabled) {
            		gui.setRecording(false);
            	} else if (type == 3 && !enabled) { // Resume playback
                    currentState = State.PLAYING;
                    run(); // Restart the playback logic
                    gui.setPlaying(true);
                    System.out.println("Playback resumed");
                } else if (type == 2 && !enabled) { // Stop playback
                    currentState = State.IDLE;
                    if (scheduledFuture != null && !scheduledFuture.isCancelled()) {
                        scheduledFuture.cancel(true);
                    }
                    gui.setPaused(false);
                    System.out.println("Playback stopped from paused state");
                }
                break;
        }
    }
}
