package laundry;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Entry point.
 *
 *   java laundry.Main                 normal scenario (console output)
 *   java laundry.Main --gui           normal scenario + live Swing visualisation
 *   java laundry.Main --congested     bonus: both kiosks dead, owner called at 30
 *   java laundry.Main --congested --gui
 */
public class Main {

    public static void main(String[] args) throws Exception {
        boolean congested = false;
        boolean gui = false;
        for (String a : args) {
            switch (a) {
                case "--congested" -> congested = true;
                case "--gui" -> gui = true;
                default -> System.err.println("Unknown option: " + a);
            }
        }

        Simulation sim = new Simulation(congested);

        if (gui) {
            // Swing rule: build the UI on the EDT via invokeLater, never on
            // the main thread - and never touch components from customer threads.
            SwingUtilities.invokeLater(() -> showGui(sim.laundry()));
        }

        sim.run(); // the main thread becomes the arrival generator + joiner
    }

    private static void showGui(Laundry laundry) {
        JFrame f = new JFrame("Smart Laundry Facility - live view");
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.setContentPane(new LaundromatPanel(laundry));
        f.pack();
        f.setSize(640, 420);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }
}
