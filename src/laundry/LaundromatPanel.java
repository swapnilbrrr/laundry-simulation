package laundry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Live Swing dashboard for the bonus GUI requirement.
 *
 * Swing components are updated only by the Event Dispatch Thread through a
 * javax.swing.Timer. Customer threads only change shared simulation state.
 */
public class LaundromatPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Color BG = new Color(0x0F172A);
    private static final Color SURFACE = new Color(0x111827);
    private static final Color SURFACE_2 = new Color(0x1E293B);
    private static final Color BORDER = new Color(0x334155);
    private static final Color TEXT = new Color(0xF8FAFC);
    private static final Color MUTED = new Color(0x94A3B8);
    private static final Color BLUE = new Color(0x38BDF8);
    private static final Color RED = new Color(0xEF4444);
    private static final Color RED_SOFT = new Color(0x451A1A);

    private static final Font TITLE = new Font("Segoe UI", Font.BOLD, 24);
    private static final Font H2 = new Font("Segoe UI", Font.BOLD, 13);
    private static final Font BODY = new Font("Segoe UI", Font.PLAIN, 12);
    private static final Font VALUE = new Font("Segoe UI", Font.BOLD, 21);
    private static final Font SMALL = new Font("Segoe UI", Font.PLAIN, 11);

    private final transient Laundry laundry;
    private final transient List<Machine> machines = new ArrayList<>();
    private final transient List<JLabel> machineCards = new ArrayList<>();

    private final JLabel modeLabel = new JLabel();
    private final JLabel elapsedLabel = new JLabel();
    private final JLabel servedValue = new JLabel("0 / 50");
    private final JLabel queueValue = new JLabel("0");
    private final JLabel averageValue = new JLabel("0.0 s");
    private final JLabel failureValue = new JLabel("0");
    private final JLabel peakValue = new JLabel("W 0  ·  D 0");
    private final JLabel ownerBanner = new JLabel();

    public LaundromatPanel(Laundry laundry) {
        this.laundry = laundry;
        setBackground(BG);
        setBorder(new EmptyBorder(22, 24, 22, 24));
        setLayout(new BorderLayout(0, 16));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        Timer timer = new Timer(200, e -> refresh());
        timer.setCoalesce(true);
        timer.start();
        refresh();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 8));
        header.setOpaque(false);

        JPanel titleBlock = new JPanel();
        titleBlock.setOpaque(false);
        titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("Smart Laundry Facility");
        title.setForeground(TEXT);
        title.setFont(TITLE);

        JLabel subtitle = new JLabel("Concurrent Programming · 50 customer simulation");
        subtitle.setForeground(MUTED);
        subtitle.setFont(BODY);

        titleBlock.add(title);
        titleBlock.add(Box.createVerticalStrut(4));
        titleBlock.add(subtitle);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        styleBadge(modeLabel, BLUE);
        styleBadge(elapsedLabel, MUTED);
        right.add(modeLabel);
        right.add(elapsedLabel);

        header.add(titleBlock, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel buildCenter() {
        JPanel center = new JPanel(new BorderLayout(0, 14));
        center.setOpaque(false);

        JPanel summary = new JPanel(new GridLayout(1, 5, 10, 0));
        summary.setOpaque(false);
        summary.add(statCard("CUSTOMERS SERVED", servedValue));
        summary.add(statCard("PAYMENT QUEUE", queueValue));
        summary.add(statCard("AVG TOTAL TIME", averageValue));
        summary.add(statCard("FAILURES", failureValue));
        summary.add(statCard("PEAK USE", peakValue));
        center.add(summary, BorderLayout.NORTH);

        JPanel resources = new JPanel();
        resources.setOpaque(false);
        resources.setLayout(new BoxLayout(resources, BoxLayout.Y_AXIS));
        resources.add(resourceSection("WASHING MACHINES", laundry.washers()));
        resources.add(Box.createVerticalStrut(12));
        resources.add(resourceSection("DRYERS", laundry.dryers()));
        resources.add(Box.createVerticalStrut(12));
        resources.add(resourceSection("PAYMENT KIOSKS", laundry.kiosks()));
        center.add(resources, BorderLayout.CENTER);
        return center;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setOpaque(false);

        ownerBanner.setFont(BODY);
        ownerBanner.setBorder(new EmptyBorder(9, 12, 9, 12));
        footer.add(ownerBanner, BorderLayout.CENTER);

        JLabel legend = new JLabel("● idle    ● in use    ● fault");
        legend.setFont(SMALL);
        legend.setForeground(MUTED);
        footer.add(legend, BorderLayout.EAST);
        return footer;
    }

    private JPanel statCard(String caption, JLabel value) {
        JPanel card = new JPanel();
        card.setBackground(SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(11, 12, 11, 12)));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JLabel label = new JLabel(caption);
        label.setForeground(MUTED);
        label.setFont(SMALL);
        value.setForeground(TEXT);
        value.setFont(VALUE);

        card.add(label);
        card.add(Box.createVerticalStrut(5));
        card.add(value);
        return card;
    }

    private JPanel resourceSection(String title, List<Machine> list) {
        JPanel section = new JPanel(new BorderLayout(0, 6));
        section.setOpaque(false);
        section.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));

        JLabel heading = new JLabel(title);
        heading.setForeground(MUTED);
        heading.setFont(H2);

        JPanel grid = new JPanel(new GridLayout(1, list.size(), 8, 0));
        grid.setOpaque(false);

        for (Machine machine : list) {
            JLabel card = new JLabel(machine.label(), JLabel.CENTER);
            card.setOpaque(true);
            card.setFont(BODY);
            card.setPreferredSize(new Dimension(110, 42));
            grid.add(card);
            machines.add(machine);
            machineCards.add(card);
        }

        section.add(heading, BorderLayout.NORTH);
        section.add(grid, BorderLayout.CENTER);
        return section;
    }

    private void styleBadge(JLabel label, Color accent) {
        label.setForeground(accent);
        label.setFont(H2);
        label.setOpaque(true);
        label.setBackground(SURFACE_2);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(6, 10, 6, 10)));
    }

    private void refresh() {
        modeLabel.setText(laundry.isCongestedScenario() ? "  CONGESTED MODE  " : "  NORMAL MODE  ");
        elapsedLabel.setText(String.format("  %.0f s  ", laundry.elapsedSeconds()));

        Stats stats = laundry.stats();
        servedValue.setText(stats.served() + " / 50");
        queueValue.setText(Integer.toString(stats.waitingForPayment().get()));
        averageValue.setText(String.format("%.1f s", stats.averageSeconds()));
        failureValue.setText(Integer.toString(stats.washerFailures() + stats.kioskFailures()));
        peakValue.setText("W " + stats.peakWashers() + "  ·  D " + stats.peakDryers());

        for (int i = 0; i < machines.size(); i++) {
            Machine m = machines.get(i);
            JLabel card = machineCards.get(i);
            if (m.isFailed()) {
                card.setBackground(RED_SOFT);
                card.setForeground(new Color(0xFCA5A5));
                card.setBorder(BorderFactory.createLineBorder(RED));
                card.setText(m.label() + " · FAULT");
            } else if (m.isBusy()) {
                card.setBackground(new Color(0x0C4A6E));
                card.setForeground(Color.WHITE);
                card.setBorder(BorderFactory.createLineBorder(BLUE));
                card.setText(m.label() + " · IN USE");
            } else {
                card.setBackground(SURFACE_2);
                card.setForeground(MUTED);
                card.setBorder(BorderFactory.createLineBorder(BORDER));
                card.setText(m.label() + " · IDLE");
            }
        }

        if (laundry.isCongestedScenario() && laundry.ownerCalled()) {
            ownerBanner.setText("OWNER CALLED · PAYMENT CONGESTION DETECTED · KIOSKS WILL RECOVER");
            ownerBanner.setForeground(new Color(0xFDE68A));
            ownerBanner.setBackground(new Color(0x422006));
        } else if (laundry.isCongestedScenario()) {
            ownerBanner.setText("CONGESTED MODE · BOTH PAYMENT KIOSKS ARE OUT OF SERVICE");
            ownerBanner.setForeground(new Color(0xFCA5A5));
            ownerBanner.setBackground(RED_SOFT);
        } else {
            ownerBanner.setText("SYSTEM RUNNING · RESOURCES ARE SHARED SAFELY BETWEEN CUSTOMER THREADS");
            ownerBanner.setForeground(new Color(0x86EFAC));
            ownerBanner.setBackground(new Color(0x052E16));
        }
    }
}
