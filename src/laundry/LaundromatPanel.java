package laundry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Bonus requirement 2: Swing GUI visualising the laundromat live.
 *
 * Concurrency notes (relevant for the report):
 * - Swing is single-threaded: ALL component mutation happens on the Event
 *   Dispatch Thread. A javax.swing.Timer fires its ActionListener on the
 *   EDT, so we poll the (volatile / synchronized) machine state every
 *   200ms and repaint from the EDT only - no cross-thread Swing access,
 *   which is the classic thread-confined UI pattern.
 * - Reading Machine.busy/failed is safe without locking because those
 *   fields are volatile: readers always see a recent, fully-written value.
 *
 * Visual design: minimal light theme - neutral surfaces, a single blue
 * accent for "in use", and red reserved exclusively for faults.
 */
public class LaundromatPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Color BG        = new Color(0x1B1F24);
    private static final Color TEXT      = new Color(0xE8EAED);
    private static final Color TEXT_SOFT = new Color(0x9AA1AC);
    private static final Color IDLE_BG   = new Color(0xFFFFFF);
    private static final Color IDLE_LINE = new Color(0xE2E5EA);
    private static final Color BUSY_BG   = new Color(0x2563EB);
    private static final Color BUSY_LINE = new Color(0x2563EB);
    private static final Color FAULT_BG  = new Color(0xFDECEC);
    private static final Color FAULT_LINE= new Color(0xF1B8B8);
    private static final Color FAULT_TXT = new Color(0xB42318);

    private static final Font CHIP_FONT = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font SECTION_FONT = new Font("Segoe UI", Font.BOLD, 11);

    private final transient Laundry laundry;
    private final transient List<Machine> machines = new ArrayList<>();
    private final transient List<JLabel> chips = new ArrayList<>();
    private final JLabel statsLabel = new JLabel(" ");

    @SuppressWarnings("this-escape") // BoxLayout legitimately needs 'this' during construction
    public LaundromatPanel(Laundry laundry) {
        this.laundry = laundry;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BG);
        setBorder(new EmptyBorder(20, 24, 20, 24));

        JLabel title = new JLabel("Smart Laundry Facility");
        title.setForeground(TEXT);
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        title.setAlignmentX(LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(18));

        add(section("Washing area"));
        addRow(laundry.washers());
        add(Box.createVerticalStrut(14));
        add(section("Drying area"));
        addRow(laundry.dryers());
        add(Box.createVerticalStrut(14));
        add(section("Payment"));
        addRow(laundry.kiosks());

        statsLabel.setForeground(TEXT_SOFT);
        statsLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statsLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(Box.createVerticalStrut(22));
        add(statsLabel);

        // Poll on the EDT - never touch Swing from customer threads.
        Timer timer = new Timer(200, e -> refresh());
        timer.start();
    }

    private JPanel section(String name) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        p.setOpaque(false);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        JLabel l = new JLabel(name.toUpperCase());
        l.setForeground(TEXT_SOFT);
        l.setFont(SECTION_FONT);
        p.add(l);
        return p;
    }

    private void addRow(List<Machine> list) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        for (Machine m : list) {
            JLabel chip = new JLabel(m.label());
            chip.setPreferredSize(new Dimension(118, 42));
            chip.setOpaque(true);
            chip.setHorizontalAlignment(JLabel.CENTER);
            chip.setFont(CHIP_FONT);
            row.add(chip);
            machines.add(m);
            chips.add(chip);
        }
        add(row);
    }

    private void refresh() {
        for (int i = 0; i < machines.size(); i++) {
            Machine m = machines.get(i);
            JLabel chip = chips.get(i);
            if (m.isFailed()) {
                chip.setBackground(FAULT_BG);
                chip.setBorder(BorderFactory.createLineBorder(FAULT_LINE, 1));
                chip.setForeground(FAULT_TXT);
                chip.setText(m.label() + "  fault");
            } else if (m.isBusy()) {
                chip.setBackground(BUSY_BG);
                chip.setBorder(BorderFactory.createLineBorder(BUSY_LINE, 1));
                chip.setForeground(Color.WHITE);
                chip.setText(m.label() + "  in use");
            } else {
                chip.setBackground(IDLE_BG);
                chip.setBorder(BorderFactory.createLineBorder(IDLE_LINE, 1));
                chip.setForeground(TEXT_SOFT);
                chip.setText(m.label() + "  idle");
            }
        }
        Stats s = laundry.stats();
        statsLabel.setText(String.format(
                "%d of 50 customers served    \u00b7    %d in payment queue    \u00b7    avg %.1f s",
                s.served(), s.waitingForPayment().get(), s.averageSeconds()));
    }
}
