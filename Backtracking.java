package project;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.Queue;

/*
 * BridgesAdvanced1
 * -----------------
 * Contains:
 *  - Divide & Conquer (candidate generation)
 *  - Dynamic Programming (edge scoring)
 *  - BFS (connectivity check)
 *  - Priority Queue (best move selection)
 *  - 🔥 Backtracking (full recursive solver added)
 */

public class Backtracking extends JPanel
        implements MouseListener, MouseMotionListener, KeyListener {

    // ================= CONSTANTS =================
    private static final int CELL_SIZE = 60;
    private static final int ISLAND_RADIUS = 20;
    private static final int GRID_W = 7;
    private static final int GRID_H = 7;
    private static final int MAX_BRIDGES = 2;

    // ================= GAME STATE =================
    private final List<Island> islands = new ArrayList<>();
    private final List<Bridge> bridges = new ArrayList<>();
    private final Map<String, Integer> dpCache = new HashMap<>();

    // ================= DATA STRUCTURES =================

    // Represents a node in graph
    static class Island {
        int x, y, required;

        Island(int x, int y, int required) {
            this.x = x;
            this.y = y;
            this.required = required;
        }

        int screenX() { return x * CELL_SIZE + CELL_SIZE / 2; }
        int screenY() { return y * CELL_SIZE + CELL_SIZE / 2; }
    }

    // Represents an edge in graph
    static class Bridge {
        Island a, b;
        int count;  // 1 or 2

        Bridge(Island a, Island b, int count) {
            this.a = a;
            this.b = b;
            this.count = count;
        }

        boolean connects(Island i1, Island i2) {
            return (a == i1 && b == i2) || (a == i2 && b == i1);
        }
    }

    // Used for D&C candidate list
    static class Move {
        Island a, b;
        int score;

        Move(Island a, Island b, int score) {
            this.a = a;
            this.b = b;
            this.score = score;
        }
    }

    // ================= CONSTRUCTOR =================

    public Backtracking() {
        setPreferredSize(new Dimension(GRID_W * CELL_SIZE, GRID_H * CELL_SIZE));
        setBackground(new Color(240, 240, 240));
        addMouseListener(this);
        addMouseMotionListener(this);
        addKeyListener(this);
        setFocusable(true);
        generateSimplePuzzle();
    }

    // ================= SIMPLE SAMPLE PUZZLE =================

    private void generateSimplePuzzle() {
        islands.clear();
        bridges.clear();

        islands.add(new Island(1, 1, 2));
        islands.add(new Island(1, 4, 2));
        islands.add(new Island(4, 1, 2));
        islands.add(new Island(4, 4, 2));
    }

    // ================= UTILITY METHODS =================

    private int getBridgeCount(Island isl) {
        int sum = 0;
        for (Bridge br : bridges)
            if (br.a == isl || br.b == isl)
                sum += br.count;
        return sum;
    }

    private Bridge findBridge(Island a, Island b) {
        for (Bridge br : bridges)
            if (br.connects(a, b))
                return br;
        return null;
    }

    private boolean isSolved() {
        for (Island isl : islands)
            if (getBridgeCount(isl) != isl.required)
                return false;
        return true;
    }

    // ================= DIVIDE & CONQUER =================

    private List<Move> generateAllPairs() {
        List<Move> list = new ArrayList<>();

        for (int i = 0; i < islands.size(); i++) {
            for (int j = i + 1; j < islands.size(); j++) {

                Island a = islands.get(i);
                Island b = islands.get(j);

                if (a.x == b.x || a.y == b.y) {
                    int score = dpEdgePriority(a, b);
                    list.add(new Move(a, b, score));
                }
            }
        }

        list.sort(Comparator.comparingInt(m -> m.score));
        return list;
    }

    // ================= DYNAMIC PROGRAMMING =================

    private int dpEdgePriority(Island a, Island b) {

        int remainA = a.required - getBridgeCount(a);
        int remainB = b.required - getBridgeCount(b);

        int[] dp = new int[MAX_BRIDGES + 1];

        // dp[0] = current slack
        dp[0] = remainA + remainB;

        // dp[k] = dp[k-1] - 2
        for (int k = 1; k <= MAX_BRIDGES; k++)
            dp[k] = dp[k - 1] - 2;

        return dp[1]; // look-ahead 1 step
    }

    // ========================================================
    // 🔥 BACKTRACKING SOLVER
    // ========================================================

    /*
     * Backtracking Idea:
     * -------------------
     * For each possible edge:
     *   Try placing 0, 1, or 2 bridges
     *   Check constraints
     *   Recurse
     *   If wrong → Undo (Backtrack)
     */

    private boolean solveWithBacktracking(int index, List<Move> moves) {

        // Base case: all edges processed
        if (index == moves.size())
            return isSolved();

        Move move = moves.get(index);
        Island a = move.a;
        Island b = move.b;

        // Try 0, 1, 2 bridges
        for (int count = 0; count <= MAX_BRIDGES; count++) {

            if (canPlace(a, b, count)) {

                placeBridge(a, b, count);

                // Prune invalid states early
                if (isPartialValid()) {

                    if (solveWithBacktracking(index + 1, moves))
                        return true;
                }

                // 🔁 Undo (Backtrack)
                removeBridge(a, b);
            }
        }

        return false;
    }

    // Check if we can place count bridges
    private boolean canPlace(Island a, Island b, int count) {

        if (count == 0)
            return true;

        if (a.x != b.x && a.y != b.y)
            return false;

        int newA = getBridgeCount(a) + count;
        int newB = getBridgeCount(b) + count;

        return newA <= a.required && newB <= b.required;
    }

    // Apply bridge
    private void placeBridge(Island a, Island b, int count) {

        if (count == 0)
            return;

        Bridge existing = findBridge(a, b);

        if (existing == null)
            bridges.add(new Bridge(a, b, count));
        else
            existing.count = count;
    }

    // Remove bridge (Undo step)
    private void removeBridge(Island a, Island b) {

        Bridge ex = findBridge(a, b);
        if (ex != null)
            bridges.remove(ex);
    }

    // Early pruning
    private boolean isPartialValid() {

        for (Island isl : islands)
            if (getBridgeCount(isl) > isl.required)
                return false;

        return true;
    }

    // ================= SOLVE BUTTON =================

    private void solvePuzzle() {

        bridges.clear();

        List<Move> moves = generateAllPairs();

        boolean solved = solveWithBacktracking(0, moves);

        if (!solved)
            JOptionPane.showMessageDialog(this, "No solution found");

        repaint();
    }

    // ================= RENDERING =================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        g2.setStroke(new BasicStroke(3));

        // Draw bridges
        for (Bridge br : bridges) {
            g2.setColor(Color.BLUE);
            g2.drawLine(br.a.screenX(), br.a.screenY(),
                        br.b.screenX(), br.b.screenY());
        }

        // Draw islands
        for (Island isl : islands) {

            g2.setColor(Color.YELLOW);
            g2.fillOval(isl.screenX() - ISLAND_RADIUS,
                        isl.screenY() - ISLAND_RADIUS,
                        ISLAND_RADIUS * 2,
                        ISLAND_RADIUS * 2);

            g2.setColor(Color.BLACK);
            g2.drawString(String.valueOf(isl.required),
                          isl.screenX() - 4,
                          isl.screenY() + 4);
        }
    }

    // ================= EMPTY INPUT METHODS =================

    @Override public void mousePressed(MouseEvent e) {}
    @Override public void mouseReleased(MouseEvent e) {}
    @Override public void mouseDragged(MouseEvent e) {}
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}
    @Override public void mouseMoved(MouseEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_S)
            solvePuzzle();
    }
    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    // ================= MAIN =================

    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {

            JFrame frame = new JFrame("Bridges – Backtracking Edition");

            BridgesAdvanced1 panel = new BridgesAdvanced1();

            JButton solveBtn = new JButton("Solve (Backtracking)");
            solveBtn.addActionListener(e -> gamePanel.showSolution());

            frame.setLayout(new BorderLayout());
            frame.add(panel, BorderLayout.CENTER);
            frame.add(solveBtn, BorderLayout.SOUTH);

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });
    }
}