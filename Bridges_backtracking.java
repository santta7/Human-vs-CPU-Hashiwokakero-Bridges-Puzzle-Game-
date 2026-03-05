package project;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

public class Bridges_backtracking extends JPanel
        implements MouseListener, MouseMotionListener, KeyListener {

    private static final int CELL_SIZE     = 60;
    private static final int ISLAND_RADIUS = 20;
    private static final int GRID_W        = 7;
    private static final int GRID_H        = 7;
    private static final int MAX_BRIDGES   = 2;

    private static final Color BG_COLOR          = new Color(240, 240, 240);
    private static final Color ISLAND_COLOR       = new Color(255, 255, 200);
    private static final Color ISLAND_DONE_COLOR  = new Color(180, 255, 180);
    private static final Color ERROR_COLOR        = new Color(255, 120, 120);
    private static final Color TEXT_COLOR         = Color.BLACK;
    private static final Color HUMAN_BRIDGE_COLOR = Color.BLUE;
    private static final Color CPU_BRIDGE_COLOR   = Color.RED;
    private static final Color BRIDGE_COLOR       = new Color(60, 60, 60);

    // Step-by-step backtracking visualization colors
    private static final Color BT_TRY_COLOR      = new Color(255, 165, 0);   // orange = trying
    private static final Color BT_SUCCESS_COLOR  = new Color(50, 205, 50);   // green  = success
    private static final Color BT_BACKTRACK_COLOR= new Color(220, 50, 50);   // red    = backtracking

    public enum Difficulty { EASY, MEDIUM, HARD }
    private Difficulty difficulty = Difficulty.MEDIUM;

    private enum Player { HUMAN, CPU }
    private Player currentPlayer = Player.HUMAN;

    private final List<Island>     islands         = new ArrayList<>();
    private final List<Bridge>     bridges         = new ArrayList<>();
    private final List<Bridge>     solutionBridges = new ArrayList<>();
    private final Stack<GameState> moveHistory     = new Stack<>();

    private Island dragStart = null;
    private Point  mousePos  = null;

    private boolean cpuThinking   = false;
    private int     humanScore    = 0;
    private int     computerScore = 0;

    // ---- Backtracking Solver fields ----
    private final Set<String> btCache   = new HashSet<>();
    private       int         btCalls   = 0;
    private       long        btTimeMs  = 0;

    // ---- Step-by-step visualization fields ----
    private volatile boolean  btStepMode       = false;
    private volatile boolean  btRunning        = false;
    private volatile int      btStepDelayMs    = 120;
    private volatile String   btStepStatus     = "";
    private volatile int      btStepDepth      = 0;
    // The "current working" bridge list shown during animation
    private List<Bridge>      btVisualBridges  = new ArrayList<>();
    // Last action type for coloring: 0=none, 1=try, 2=backtrack, 3=solved
    private volatile int      btLastAction     = 0;
    // The specific bridge being tried/backtracked right now
    private Island            btHighlightA     = null;
    private Island            btHighlightB     = null;
    // Speed control slider reference
    private JSlider           speedSlider      = null;

    // ====================================================
    //  DATA STRUCTURES
    // ====================================================

    static class GameState {
        List<Bridge> bridges;
        int humanScore, computerScore;
        Player currentPlayer;
        GameState(List<Bridge> bridges, int hs, int cs, Player cp) {
            this.bridges = new ArrayList<>();
            for (Bridge b : bridges) {
                Bridge copy = new Bridge(b.a, b.b, b.count);
                copy.owner = b.owner;
                this.bridges.add(copy);
            }
            this.humanScore    = hs;
            this.computerScore = cs;
            this.currentPlayer = cp;
        }
    }

    static class Island {
        int x, y, required;
        Island(int x, int y, int required) { this.x = x; this.y = y; this.required = required; }
        int screenX() { return x * CELL_SIZE + CELL_SIZE / 2; }
        int screenY() { return y * CELL_SIZE + CELL_SIZE / 2; }
    }

    static class Bridge {
        Island a, b;
        int count;
        int owner;
        Bridge(Island a, Island b, int count) { this.a = a; this.b = b; this.count = count; }
        boolean connects(Island i1, Island i2) { return (a==i1&&b==i2)||(a==i2&&b==i1); }
        boolean isHorizontal() { return a.y == b.y; }
    }

    static class Move {
        Island a, b;
        int urgency;
        Move(Island a, Island b, int urgency) { this.a = a; this.b = b; this.urgency = urgency; }
    }

    static class MoveDelta {
        Bridge bridge;
        boolean created;
        int previousCount;
        int previousOwner;
        MoveDelta(Bridge bridge, boolean created, int previousCount, int previousOwner) {
            this.bridge        = bridge;
            this.created       = created;
            this.previousCount = previousCount;
            this.previousOwner = previousOwner;
        }
    }

    // ====================================================
    //  CONSTRUCTOR
    // ====================================================

    public Bridges_backtracking() {
        setPreferredSize(new Dimension(GRID_W * CELL_SIZE, GRID_H * CELL_SIZE + 60));
        setBackground(BG_COLOR);
        addMouseListener(this);
        addMouseMotionListener(this);
        addKeyListener(this);
        setFocusable(true);
        generatePuzzle();
    }

    public void setDifficulty(Difficulty d) { this.difficulty = d; generatePuzzle(); }

    // ====================================================
    //  PUZZLE GENERATION
    // ====================================================

    private void generatePuzzle() {
        islands.clear(); bridges.clear(); solutionBridges.clear(); moveHistory.clear();
        currentPlayer = Player.HUMAN; cpuThinking = false;
        humanScore = 0; computerScore = 0;
        btRunning = false; btStepMode = false; btVisualBridges.clear();
        btLastAction = 0; btHighlightA = null; btHighlightB = null;

        Random rand = new Random();
        boolean[][] occupied = new boolean[GRID_W][GRID_H];
        int numIslands = switch (difficulty) {
            case EASY   ->  6 + rand.nextInt(3);
            case MEDIUM ->  8 + rand.nextInt(5);
            case HARD   -> 10 + rand.nextInt(5);
        };

        while (islands.size() < numIslands) {
            int x = rand.nextInt(GRID_W), y = rand.nextInt(GRID_H);
            if (!occupied[x][y]) { islands.add(new Island(x, y, 0)); occupied[x][y] = true; }
        }
        if (islands.isEmpty()) return;

        List<Bridge> solution = new ArrayList<>();
        Set<Island>  connected = new HashSet<>();
        connected.add(islands.get(0));

        while (connected.size() < islands.size()) {
            List<int[]> candidates = new ArrayList<>();
            for (Island from : connected)
                for (Island to : islands) {
                    if (connected.contains(to)) continue;
                    if (canConnectInSolution(from, to, solution))
                        candidates.add(new int[]{islands.indexOf(from), islands.indexOf(to)});
                }
            if (candidates.isEmpty()) { generatePuzzle(); return; }
            int[] ch = candidates.get(rand.nextInt(candidates.size()));
            Island a = islands.get(ch[0]), b = islands.get(ch[1]);
            solution.add(new Bridge(a, b, rand.nextBoolean() ? 1 : 2));
            connected.add(b);
        }

        for (int k = 0; k < islands.size() / 2; k++) {
            Island a = islands.get(rand.nextInt(islands.size()));
            Island b = islands.get(rand.nextInt(islands.size()));
            if (a == b || !canConnectInSolution(a, b, solution)) continue;
            Bridge ex = findBridge(a, b, solution);
            if (ex == null)                  solution.add(new Bridge(a, b, rand.nextBoolean() ? 1 : 2));
            else if (ex.count < MAX_BRIDGES) ex.count++;
        }

        for (Island isl : islands) {
            int deg = 0;
            for (Bridge br : solution) if (br.a == isl || br.b == isl) deg += br.count;
            isl.required = deg;
        }

        islands.removeIf(i -> i.required == 0);
        if (islands.size() < 4) { generatePuzzle(); return; }

        Set<Island> islandSet = new HashSet<>(islands);
        for (Bridge br : solution) {
            if (islandSet.contains(br.a) && islandSet.contains(br.b))
                solutionBridges.add(new Bridge(br.a, br.b, br.count));
        }

        for (Island isl : islands) {
            int deg = 0;
            for (Bridge br : solutionBridges) if (br.a == isl || br.b == isl) deg += br.count;
            isl.required = deg;
        }
        islands.removeIf(i -> i.required == 0);
        if (islands.size() < 4) { generatePuzzle(); return; }

        repaint();
    }

    private boolean canConnectInSolution(Island a, Island b, List<Bridge> solution) {
        if (a == b || (a.x != b.x && a.y != b.y)) return false;
        for (Bridge br : solution) if (bridgesCross(a, b, br.a, br.b)) return false;
        for (Island c : islands) {
            if (c == a || c == b) continue;
            if (islandBetween(a, b, c)) return false;
        }
        return true;
    }

    public void restartPuzzle() {
        btRunning = false; btStepMode = false;
        bridges.clear(); moveHistory.clear();
        currentPlayer = Player.HUMAN; cpuThinking = false;
        humanScore = 0; computerScore = 0;
        btVisualBridges.clear(); btLastAction = 0;
        btHighlightA = null; btHighlightB = null;
        repaint();
    }

    public void showSolution() {
        if (solutionBridges.isEmpty()) return;
        bridges.clear();
        for (Bridge sb : solutionBridges) {
            Bridge c = new Bridge(sb.a, sb.b, sb.count); c.owner = 0; bridges.add(c);
        }
        repaint();
        JOptionPane.showMessageDialog(this, "Solution shown.", "Solve",
                JOptionPane.INFORMATION_MESSAGE);
    }

    // ====================================================
    //  UNDO
    // ====================================================

    private void saveGameState() {
        moveHistory.push(new GameState(bridges, humanScore, computerScore, currentPlayer));
    }

    public void undoLastMove() {
        if (moveHistory.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No moves to undo.", "Undo",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        GameState state = moveHistory.pop();
        bridges.clear();
        for (Bridge b : state.bridges) {
            Bridge copy = new Bridge(b.a, b.b, b.count); copy.owner = b.owner; bridges.add(copy);
        }
        humanScore    = state.humanScore;
        computerScore = state.computerScore;
        currentPlayer = state.currentPlayer;
        cpuThinking   = false;
        repaint();
    }

    // ====================================================
    //  GEOMETRY / VALIDATION
    // ====================================================

    private boolean canConnect(Island a, Island b, List<Bridge> extra) {
        if (a == b || (a.x != b.x && a.y != b.y)) return false;
        for (Bridge br : extra)   if (bridgesCross(a, b, br.a, br.b)) return false;
        for (Bridge br : bridges) if (bridgesCross(a, b, br.a, br.b)) return false;
        for (Island c : islands) {
            if (c == a || c == b) continue;
            if (islandBetween(a, b, c)) return false;
        }
        return true;
    }

    private boolean islandBetween(Island a, Island b, Island c) {
        if (a.x == b.x && c.x == a.x) {
            int lo = Math.min(a.y, b.y), hi = Math.max(a.y, b.y);
            return c.y > lo && c.y < hi;
        }
        if (a.y == b.y && c.y == a.y) {
            int lo = Math.min(a.x, b.x), hi = Math.max(a.x, b.x);
            return c.x > lo && c.x < hi;
        }
        return false;
    }

    private boolean bridgesCross(Island a1, Island b1, Island a2, Island b2) {
        boolean h1 = (a1.y == b1.y), h2 = (a2.y == b2.y);
        if (h1 == h2) return false;
        Island h, hE, v, vE;
        if (h1) { h = a1; hE = b1; v = a2; vE = b2; }
        else    { h = a2; hE = b2; v = a1; vE = b1; }
        int hLo = Math.min(h.x, hE.x), hHi = Math.max(h.x, hE.x);
        int vLo = Math.min(v.y, vE.y), vHi = Math.max(v.y, vE.y);
        return v.x > hLo && v.x < hHi && h.y > vLo && h.y < vHi;
    }

    private boolean wouldBlockSolution(Island a, Island b) {
        for (Bridge sb : solutionBridges)
            if (bridgesCross(a, b, sb.a, sb.b)) return true;
        return false;
    }

    private Bridge findBridge(Island a, Island b, List<Bridge> list) {
        for (Bridge br : list) if (br.connects(a, b)) return br;
        return null;
    }
    private Bridge findBridge(Island a, Island b) { return findBridge(a, b, bridges); }

    private int getBridgeCount(Island isl) {
        int s = 0;
        for (Bridge br : bridges) if (br.a == isl || br.b == isl) s += br.count;
        return s;
    }

    // ====================================================
    //  CONNECTIVITY (BFS)
    // ====================================================

    private boolean isConnected() {
        if (islands.isEmpty()) return true;
        Map<Island, List<Island>> adj = new HashMap<>();
        for (Island isl : islands) adj.put(isl, new ArrayList<>());
        for (Bridge br : bridges) { adj.get(br.a).add(br.b); adj.get(br.b).add(br.a); }
        Set<Island>   visited = new HashSet<>();
        Queue<Island> q       = new LinkedList<>();
        visited.add(islands.get(0)); q.add(islands.get(0));
        while (!q.isEmpty()) {
            Island u = q.poll();
            for (Island v : adj.get(u)) if (visited.add(v)) q.add(v);
        }
        return visited.size() == islands.size();
    }

    private boolean isSolved() {
        if (!isConnected()) return false;
        for (Island isl : islands) if (getBridgeCount(isl) != isl.required) return false;
        return true;
    }

    // ====================================================
    //  MINIMAX (unchanged)
    // ====================================================

    private List<Move> collectLegalMoves() {
        List<Move> moves = new ArrayList<>();
        for (int i = 0; i < islands.size(); i++) {
            Island a  = islands.get(i);
            int remA  = a.required - getBridgeCount(a);
            if (remA <= 0) continue;
            for (int j = i + 1; j < islands.size(); j++) {
                Island b = islands.get(j);
                int remB = b.required - getBridgeCount(b);
                if (remB <= 0) continue;
                Bridge ex = findBridge(a, b);
                if (ex != null && ex.count >= MAX_BRIDGES) continue;
                if (!canConnect(a, b, new ArrayList<>())) continue;
                moves.add(new Move(a, b, remA + remB));
            }
        }
        moves.sort(Comparator.comparingInt(m -> m.urgency));
        return moves;
    }

    private int getSearchDepth() {
        return switch (difficulty) {
            case EASY   -> 2;
            case MEDIUM -> 4;
            case HARD   -> 6;
        };
    }

    private Move chooseMoveByBacktracking() {
        List<Move> moves = collectLegalMoves();
        if (moves.isEmpty()) return null;
        Move bestMove  = null;
        int  bestScore = Integer.MIN_VALUE;
        int  alpha     = Integer.MIN_VALUE;
        int  beta      = Integer.MAX_VALUE;
        for (Move move : moves) {
            if (wouldBlockSolution(move.a, move.b)) continue;
            MoveDelta delta = applyTemporaryMove(move.a, move.b);
            if (delta == null) continue;
            int score = minimax(getSearchDepth() - 1, false, alpha, beta);
            undoTemporaryMove(delta);
            if (score > bestScore) { bestScore = score; bestMove = move; }
            alpha = Math.max(alpha, bestScore);
        }
        if (bestMove == null) {
            for (Move move : moves) {
                MoveDelta delta = applyTemporaryMove(move.a, move.b);
                if (delta == null) continue;
                int score = minimax(getSearchDepth() - 1, false, alpha, beta);
                undoTemporaryMove(delta);
                if (score > bestScore) { bestScore = score; bestMove = move; }
            }
        }
        return bestMove;
    }

    private int minimax(int depth, boolean isMaximising, int alpha, int beta) {
        if (depth == 0 || isSolved()) return evaluateBoard();
        List<Move> moves = collectLegalMoves();
        if (moves.isEmpty()) return evaluateBoard();
        if (isMaximising) {
            int best = Integer.MIN_VALUE;
            for (Move move : moves) {
                if (wouldBlockSolution(move.a, move.b)) continue;
                MoveDelta delta = applyTemporaryMove(move.a, move.b);
                if (delta == null) continue;
                best  = Math.max(best, minimax(depth - 1, false, alpha, beta));
                alpha = Math.max(alpha, best);
                undoTemporaryMove(delta);
                if (beta <= alpha) break;
            }
            return (best == Integer.MIN_VALUE) ? evaluateBoard() : best;
        } else {
            int best = Integer.MAX_VALUE;
            for (Move move : moves) {
                MoveDelta delta = applyTemporaryMove(move.a, move.b);
                if (delta == null) continue;
                best = Math.min(best, minimax(depth - 1, true, alpha, beta));
                beta = Math.min(beta, best);
                undoTemporaryMove(delta);
                if (beta <= alpha) break;
            }
            return (best == Integer.MAX_VALUE) ? evaluateBoard() : best;
        }
    }

    private MoveDelta applyTemporaryMove(Island a, Island b) {
        Bridge ex = findBridge(a, b);
        if (ex == null) {
            Bridge created = new Bridge(a, b, 1);
            bridges.add(created);
            return new MoveDelta(created, true, 1, 0);
        }
        if (ex.count >= MAX_BRIDGES) return null;
        int prevCount = ex.count;
        int prevOwner = ex.owner;
        ex.count++;
        return new MoveDelta(ex, false, prevCount, prevOwner);
    }

    private void undoTemporaryMove(MoveDelta delta) {
        if (delta.created) {
            bridges.remove(delta.bridge);
        } else {
            delta.bridge.count = delta.previousCount;
            delta.bridge.owner = delta.previousOwner;
        }
    }

    private int evaluateBoard() {
        int score = 0;
        for (Island isl : islands) {
            int diff = isl.required - getBridgeCount(isl);
            if (diff == 0)     score += 30;
            else if (diff > 0) score -= diff * 5;
            else               score += diff * 80;
        }
        if (isConnected()) score += 50;
        return score;
    }

    // ====================================================
    //  CPU MOVE
    // ====================================================

    private void makeCPUMove() {
        if (isSolved()) return;
        Move best = chooseMoveByBacktracking();
        if (best != null) addBridgeForCPU(best.a, best.b);
    }

    private void addBridgeForCPU(Island a, Island b) {
        if (!canConnect(a, b, new ArrayList<>())) return;
        int prevA = getBridgeCount(a), prevB = getBridgeCount(b);
        Bridge ex = findBridge(a, b);
        if (ex == null) {
            Bridge nb = new Bridge(a, b, 1); nb.owner = 2; bridges.add(nb);
        } else if (ex.count < MAX_BRIDGES) {
            ex.count++; ex.owner = 2;
        } else { return; }
        if (prevA < a.required && getBridgeCount(a) == a.required) computerScore++;
        if (prevB < b.required && getBridgeCount(b) == b.required) computerScore++;
        repaint();
        if (isSolved()) showSolvedPopup();
    }

    // ====================================================
    //  HUMAN MOVE
    // ====================================================

    private int addOrRemoveBridgeHuman(Island a, Island b) {
        if (!canConnect(a, b, new ArrayList<>())) return 0;
        if (wouldBlockSolution(a, b)) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "This bridge crosses a solution path!\n" +
                    "The puzzle may become harder to solve.\nPlace it anyway?",
                    "Warning", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return 0;
        }
        saveGameState();
        int prevA = getBridgeCount(a), prevB = getBridgeCount(b);
        Bridge ex = findBridge(a, b);
        int delta;
        if (ex == null) {
            Bridge nb = new Bridge(a, b, 1); nb.owner = 1; bridges.add(nb); delta = 1;
        } else if (ex.count < MAX_BRIDGES) {
            ex.count++; ex.owner = 1; delta = 1;
        } else {
            bridges.remove(ex); delta = -1;
        }
        if (prevA < a.required && getBridgeCount(a) == a.required) humanScore++;
        if (prevB < b.required && getBridgeCount(b) == b.required) humanScore++;
        repaint();
        if (isSolved()) showSolvedPopup();
        return delta;
    }

    private void showSolvedPopup() {
        String winner;
        if      (humanScore > computerScore) winner = "Human wins!";
        else if (computerScore > humanScore) winner = "Computer wins!";
        else                                 winner = "It's a draw!";
        JOptionPane.showMessageDialog(this,
                "Puzzle solved!\n\nHuman: " + humanScore +
                " pts\nComputer: " + computerScore + " pts\n\n" + winner,
                "Solved", JOptionPane.INFORMATION_MESSAGE);
    }

    // ====================================================
    //  MOUSE INPUT
    // ====================================================

    private Island getIslandAt(int sx, int sy) {
        for (Island isl : islands) {
            int dx = sx - isl.screenX(), dy = sy - isl.screenY();
            if (dx*dx + dy*dy <= ISLAND_RADIUS * ISLAND_RADIUS) return isl;
        }
        return null;
    }

    private Island getIslandInDirection(Island from, int dx, int dy) {
        Island best = null; int bestDist = Integer.MAX_VALUE;
        for (Island isl : islands) {
            if (isl == from) continue;
            int ix = isl.x - from.x, iy = isl.y - from.y;
            if (dx != 0 && (Math.signum(ix) != Math.signum(dx) || iy != 0)) continue;
            if (dy != 0 && (Math.signum(iy) != Math.signum(dy) || ix != 0)) continue;
            int dist = Math.abs(ix) + Math.abs(iy);
            if (dist < bestDist && canConnect(from, isl, new ArrayList<>())) {
                bestDist = dist; best = isl;
            }
        }
        return best;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (cpuThinking || currentPlayer != Player.HUMAN || btRunning) return;
        requestFocusInWindow();
        Island hit = getIslandAt(e.getX(), e.getY());
        if (hit != null && SwingUtilities.isLeftMouseButton(e)) {
            dragStart = hit; mousePos = e.getPoint();
        }
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (cpuThinking || currentPlayer != Player.HUMAN || btRunning) {
            dragStart = null; mousePos = null; return;
        }
        boolean addedBridge = false;
        if (dragStart != null && mousePos != null) {
            int dx = mousePos.x - dragStart.screenX(), dy = mousePos.y - dragStart.screenY();
            if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                int dirX = (Math.abs(dx) >= Math.abs(dy)) ? (dx > 0 ? 1 : -1) : 0;
                int dirY = (Math.abs(dy) >  Math.abs(dx)) ? (dy > 0 ? 1 : -1) : 0;
                Island target = getIslandInDirection(dragStart, dirX, dirY);
                if (target != null && addOrRemoveBridgeHuman(dragStart, target) > 0)
                    addedBridge = true;
            }
        }
        dragStart = null; mousePos = null; repaint();
        if (addedBridge && !isSolved()) {
            currentPlayer = Player.CPU; cpuThinking = true; repaint();
            SwingUtilities.invokeLater(() -> {
                makeCPUMove();
                cpuThinking   = false;
                currentPlayer = Player.HUMAN;
                repaint();
            });
        }
    }

    @Override public void mouseDragged(MouseEvent e) {
        if (cpuThinking || currentPlayer != Player.HUMAN || btRunning) return;
        if (dragStart != null) { mousePos = e.getPoint(); repaint(); }
    }

    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}
    @Override public void mouseMoved(MouseEvent e) {}

    // ====================================================
    //  KEYBOARD
    // ====================================================

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_N)                      { generatePuzzle(); repaint(); }
        if (e.getKeyCode() == KeyEvent.VK_Z && e.isControlDown()) { undoLastMove(); }
    }
    @Override public void keyReleased(KeyEvent e) {}
    @Override public void keyTyped(KeyEvent e) {}

    // ====================================================
    //  RENDERING
    // ====================================================

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // During backtrack visualization, draw btVisualBridges instead of real bridges
        List<Bridge> drawBridges = btStepMode ? btVisualBridges : bridges;

        // Draw bridges
        g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Bridge br : drawBridges) {
            int x1 = br.a.screenX(), y1 = br.a.screenY(),
                x2 = br.b.screenX(), y2 = br.b.screenY();

            Color bridgeCol;
            if (btStepMode && btHighlightA != null && btHighlightB != null
                    && br.connects(btHighlightA, btHighlightB)) {
                bridgeCol = (btLastAction == 2) ? BT_BACKTRACK_COLOR
                          : (btLastAction == 3) ? BT_SUCCESS_COLOR
                          :                       BT_TRY_COLOR;
            } else if (btStepMode) {
                bridgeCol = new Color(100, 100, 200); // previously placed bridges in BT mode
            } else {
                bridgeCol = br.owner == 1 ? HUMAN_BRIDGE_COLOR
                          : br.owner == 2 ? CPU_BRIDGE_COLOR
                          :                 BRIDGE_COLOR;
            }
            g2.setColor(bridgeCol);

            if (br.count == 1) {
                g2.drawLine(x1, y1, x2, y2);
            } else {
                int off = 4;
                if (br.isHorizontal()) {
                    g2.drawLine(x1, y1 - off, x2, y2 - off);
                    g2.drawLine(x1, y1 + off, x2, y2 + off);
                } else {
                    g2.drawLine(x1 - off, y1, x2 - off, y2);
                    g2.drawLine(x1 + off, y1, x2 + off, y2);
                }
            }
        }

        // Draw islands
        for (Island isl : islands) {
            int cx = isl.screenX(), cy = isl.screenY();
            int deg = 0;
            for (Bridge br : drawBridges) if (br.a == isl || br.b == isl) deg += br.count;
            Color fill = (deg == isl.required) ? ISLAND_DONE_COLOR
                       : (deg >  isl.required) ? ERROR_COLOR
                       :                         ISLAND_COLOR;
            g2.setColor(fill);
            g2.fillOval(cx - ISLAND_RADIUS, cy - ISLAND_RADIUS, 2*ISLAND_RADIUS, 2*ISLAND_RADIUS);
            g2.setColor(Color.DARK_GRAY);
            g2.setStroke(new BasicStroke(2));
            g2.drawOval(cx - ISLAND_RADIUS, cy - ISLAND_RADIUS, 2*ISLAND_RADIUS, 2*ISLAND_RADIUS);
            g2.setColor(TEXT_COLOR);
            g2.setFont(new Font("Arial", Font.BOLD, 16));
            String s = String.valueOf(isl.required);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(s, cx - fm.stringWidth(s)/2, cy + fm.getAscent()/2 - 2);
        }

        // Drag preview
        if (dragStart != null && mousePos != null) {
            g2.setColor(new Color(80, 80, 200, 150));
            g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    0, new float[]{5, 5}, 0));
            g2.drawLine(dragStart.screenX(), dragStart.screenY(), mousePos.x, mousePos.y);
        }

        // Legend during BT step mode
        if (btStepMode) {
            int lx = 5, ly = getHeight() - 50;
            g2.setFont(new Font("Arial", Font.BOLD, 11));
            g2.setColor(BT_TRY_COLOR);      g2.fillRect(lx,    ly, 12, 12); g2.setColor(Color.DARK_GRAY); g2.drawString("Trying",      lx+15, ly+11);
            g2.setColor(BT_BACKTRACK_COLOR); g2.fillRect(lx+80, ly, 12, 12); g2.setColor(Color.DARK_GRAY); g2.drawString("Backtracking",lx+95, ly+11);
            g2.setColor(BT_SUCCESS_COLOR);  g2.fillRect(lx+210,ly, 12, 12); g2.setColor(Color.DARK_GRAY); g2.drawString("Solved!",     lx+225,ly+11);
        }

        // Status bar (two rows for BT mode)
        g2.setColor(Color.DARK_GRAY);
        g2.setFont(new Font("Arial", Font.PLAIN, 11));
        if (btStepMode && btRunning) {
            g2.drawString("BACKTRACKING LIVE  |  Depth: " + btStepDepth
                    + "  |  Calls: " + btCalls
                    + "  |  " + btStepStatus,
                    5, getHeight() - 26);
            g2.drawString("  Bridges placed: " + btVisualBridges.size()
                    + "  |  Speed slider controls animation delay",
                    5, getHeight() - 10);
        } else if (btStepMode && !btRunning) {
            g2.drawString("BACKTRACKING DONE  |  Calls: " + btCalls
                    + "  |  Time: " + btTimeMs + "ms  |  " + btStepStatus,
                    5, getHeight() - 26);
            g2.drawString("Press 'New Game' or 'Restart' to play normally.",
                    5, getHeight() - 10);
        } else {
            String turn = cpuThinking ? "Computer thinking (Minimax + Alpha-Beta)..."
                    : currentPlayer == Player.HUMAN ? "Your turn" : "Computer's turn";
            g2.drawString(turn
                    + "  |  Human: "    + humanScore    + " pts"
                    + "  |  Computer: " + computerScore + " pts"
                    + "  |  N = new puzzle   Ctrl+Z = undo",
                    5, getHeight() - 6);
        }
    }

    // ====================================================
    //  STEP-BY-STEP BACKTRACKING VISUALIZATION
    //
    //  Instead of computing silently and showing the final answer,
    //  we run the DFS on a background thread and after each
    //  "try bridge" or "backtrack" step we:
    //    1. Update btVisualBridges (the list drawn on screen)
    //    2. Set btHighlightA/B and btLastAction for coloring
    //    3. Call repaint() and sleep btStepDelayMs milliseconds
    //
    //  This makes every step visible in real time.
    // ====================================================

    public void solveByBacktracking() {
        if (btRunning) return;

        btStepMode  = true;
        btRunning   = true;
        btCache.clear();
        btCalls     = 0;
        btStepDepth = 0;
        btStepStatus = "Starting...";
        btVisualBridges.clear();
        btLastAction    = 0;
        btHighlightA    = null;
        btHighlightB    = null;
        repaint();

        List<Bridge> start = deepCopyBridges(bridges); // start from current board state

        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            List<Bridge> solution = btSolveAnimated(start, 0);
            btTimeMs  = System.currentTimeMillis() - t0;
            btRunning = false;

            SwingUtilities.invokeLater(() -> {
                btHighlightA = null;
                btHighlightB = null;
                if (solution != null) {
                    // Show final solution in green
                    btVisualBridges.clear();
                    for (Bridge b : solution) {
                        Bridge c = new Bridge(b.a, b.b, b.count); c.owner = 0;
                        btVisualBridges.add(c);
                    }
                    btLastAction = 3;
                    btStepStatus = "SOLVED! ✓";
                    repaint();
                    JOptionPane.showMessageDialog(Bridges_backtracking.this,
                            "Backtracking found a solution!\n\n" +
                            "Recursive calls : " + btCalls + "\n" +
                            "Time taken      : " + btTimeMs + " ms",
                            "Backtrack Solved", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    btStepStatus = "No solution found.";
                    repaint();
                    JOptionPane.showMessageDialog(Bridges_backtracking.this,
                            "Backtracking could not find a solution.\n\n" +
                            "Recursive calls : " + btCalls + "\n" +
                            "Time taken      : " + btTimeMs + " ms\n\n" +
                            "Try pressing 'Solve' to see the intended solution.",
                            "No Solution Found", JOptionPane.WARNING_MESSAGE);
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    /**
     * Animated backtracking DFS.
     * Identical logic to btSolve() but paints each step with a configurable delay.
     */
    private List<Bridge> btSolveAnimated(List<Bridge> current, int depth) {
        if (!btRunning) return null; // stopped externally

        btCalls++;
        btStepDepth = depth;

        if (btIsSolved(current)) return current;
        if (!btIsValid(current)) return null;
        if (depth > 80)          return null;

        String key = btStateKey(current);
        if (btCache.contains(key)) return null;

        List<int[]> moves = btMoves(current);
        if (moves.isEmpty()) return null;

        for (int[] mv : moves) {
            if (!btRunning) return null;

            Island a = islands.get(mv[0]);
            Island b = islands.get(mv[1]);

            List<Bridge> next = deepCopyBridges(current);
            Bridge ex = btFindBridge(a, b, next);
            if (ex == null) {
                next.add(new Bridge(a, b, 1));
            } else if (ex.count < MAX_BRIDGES) {
                ex.count++;
            } else {
                continue;
            }

            // ---- VISUALIZE: TRYING this bridge ----
            btHighlightA = a;
            btHighlightB = b;
            btLastAction = 1; // orange = trying
            btStepStatus = "Trying: (" + a.x + "," + a.y + ") → (" + b.x + "," + b.y + ")  depth=" + depth;
            // Snapshot: show bridges placed so far in this path
            final List<Bridge> snap = deepCopyBridges(next);
            SwingUtilities.invokeLater(() -> {
                btVisualBridges.clear();
                btVisualBridges.addAll(snap);
                repaint();
            });
            sleep(btStepDelayMs);

            List<Bridge> result = btSolveAnimated(next, depth + 1);
            if (result != null) return result;

            // ---- VISUALIZE: BACKTRACKING from this bridge ----
            btHighlightA = a;
            btHighlightB = b;
            btLastAction = 2; // red = backtracking
            btStepStatus = "Backtrack: (" + a.x + "," + a.y + ") → (" + b.x + "," + b.y + ")  depth=" + depth;
            final List<Bridge> snapBack = deepCopyBridges(current);
            SwingUtilities.invokeLater(() -> {
                btVisualBridges.clear();
                btVisualBridges.addAll(snapBack);
                repaint();
            });
            sleep(btStepDelayMs / 2); // backtrack flash is shorter
        }

        btCache.add(key);
        return null;
    }

    private void sleep(int ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ====================================================
    //  BACKTRACKING HELPERS (unchanged logic)
    // ====================================================

    private boolean btIsSolved(List<Bridge> bl) {
        for (Island isl : islands) {
            int deg = 0;
            for (Bridge b : bl) if (b.a == isl || b.b == isl) deg += b.count;
            if (deg != isl.required) return false;
        }
        return btIsConnected(bl);
    }

    private boolean btIsValid(List<Bridge> bl) {
        for (Island isl : islands) {
            int deg = 0;
            for (Bridge b : bl) if (b.a == isl || b.b == isl) deg += b.count;
            if (deg > isl.required) return false;
        }
        for (int i = 0; i < bl.size(); i++)
            for (int j = i + 1; j < bl.size(); j++)
                if (bridgesCross(bl.get(i).a, bl.get(i).b, bl.get(j).a, bl.get(j).b)) return false;
        return true;
    }

    private boolean btIsConnected(List<Bridge> bl) {
        if (islands.isEmpty()) return true;
        Set<Island> visited = new HashSet<>();
        Queue<Island> q = new LinkedList<>();
        visited.add(islands.get(0)); q.add(islands.get(0));
        while (!q.isEmpty()) {
            Island u = q.poll();
            for (Bridge b : bl) {
                Island nb = null;
                if (b.a == u) nb = b.b;
                else if (b.b == u) nb = b.a;
                if (nb != null && visited.add(nb)) q.add(nb);
            }
        }
        return visited.size() == islands.size();
    }

    private List<int[]> btMoves(List<Bridge> bl) {
        List<int[]> moves = new ArrayList<>();
        for (int i = 0; i < islands.size(); i++) {
            Island a = islands.get(i);
            int degA = 0;
            for (Bridge b : bl) if (b.a == a || b.b == a) degA += b.count;
            if (degA >= a.required) continue;
            for (int j = i + 1; j < islands.size(); j++) {
                Island b = islands.get(j);
                int degB = 0;
                for (Bridge br : bl) if (br.a == b || br.b == b) degB += br.count;
                if (degB >= b.required) continue;
                Bridge ex = btFindBridge(a, b, bl);
                if (ex != null && ex.count >= MAX_BRIDGES) continue;
                if (!btCanConnect(a, b, bl)) continue;
                int priority = 0;
                if (degA + 1 == a.required) priority += 10;
                if (degB + 1 == b.required) priority += 10;
                int optA = btOptions(a, bl), optB = btOptions(b, bl);
                int remA = a.required - degA,  remB = b.required - degB;
                if (optA <= remA) priority += 8;
                if (optB <= remB) priority += 8;
                moves.add(new int[]{i, j, priority});
            }
        }
        moves.sort((x, y) -> Integer.compare(y[2], x[2]));
        return moves;
    }

    private int btOptions(Island isl, List<Bridge> bl) {
        int count = 0;
        for (Island other : islands) {
            if (other == isl) continue;
            Bridge ex = btFindBridge(isl, other, bl);
            if (ex != null && ex.count >= MAX_BRIDGES) continue;
            if (btCanConnect(isl, other, bl)) count++;
        }
        return count;
    }

    private boolean btCanConnect(Island a, Island b, List<Bridge> bl) {
        if (a == b || (a.x != b.x && a.y != b.y)) return false;
        for (Bridge br : bl) if (bridgesCross(a, b, br.a, br.b)) return false;
        for (Island c : islands) {
            if (c == a || c == b) continue;
            if (islandBetween(a, b, c)) return false;
        }
        return true;
    }

    private Bridge btFindBridge(Island a, Island b, List<Bridge> bl) {
        for (Bridge br : bl) if (br.connects(a, b)) return br;
        return null;
    }

    private List<Bridge> deepCopyBridges(List<Bridge> src) {
        List<Bridge> copy = new ArrayList<>();
        for (Bridge b : src) {
            Bridge c = new Bridge(b.a, b.b, b.count);
            c.owner = b.owner;
            copy.add(c);
        }
        return copy;
    }

    private String btStateKey(List<Bridge> bl) {
        List<String> parts = new ArrayList<>();
        for (Bridge b : bl) {
            String ea = b.a.x + "," + b.a.y;
            String eb = b.b.x + "," + b.b.y;
            String part = (ea.compareTo(eb) <= 0)
                    ? ea + "-" + eb + ":" + b.count
                    : eb + "-" + ea + ":" + b.count;
            parts.add(part);
        }
        Collections.sort(parts);
        return String.join(";", parts);
    }

    // ====================================================
    //  MAIN
    // ====================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Bridges - Step-by-Step Backtracking Edition");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            Bridges_backtracking gamePanel = new Bridges_backtracking();

            JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JComboBox<String> typeBox = new JComboBox<>(
                    new String[]{"7x7 Easy", "7x7 Medium", "7x7 Hard"});
            JButton newBtn     = new JButton("New Game");
            JButton restartBtn = new JButton("Restart");
            JButton solveBtn   = new JButton("Solve");
            JButton undoBtn    = new JButton("Undo (Ctrl+Z)");

            // The key button — now triggers animated step-by-step backtracking
            JButton btBtn = new JButton("▶ Backtrack (Live)");
            btBtn.setBackground(new Color(255, 200, 80));
            btBtn.setForeground(Color.BLACK);
            btBtn.setFont(btBtn.getFont().deriveFont(Font.BOLD));

            // Speed slider: left=slow (500ms), right=fast (10ms)
            JLabel speedLabel = new JLabel("Speed:");
            JSlider speedSlider = new JSlider(JSlider.HORIZONTAL, 10, 500, 120);
            speedSlider.setInverted(true); // left=slow, right=fast
            speedSlider.setPreferredSize(new Dimension(100, 25));
            speedSlider.setToolTipText("Animation speed (left=slow, right=fast)");
            speedSlider.addChangeListener(e -> gamePanel.btStepDelayMs = speedSlider.getValue());
            gamePanel.speedSlider = speedSlider;

            topBar.add(new JLabel("Difficulty:")); topBar.add(typeBox);
            topBar.add(newBtn); topBar.add(restartBtn);
            topBar.add(solveBtn); topBar.add(undoBtn);
            topBar.add(btBtn);
            topBar.add(speedLabel); topBar.add(speedSlider);

            typeBox.addActionListener(e -> {
                String sel = (String) typeBox.getSelectedItem();
                if (sel == null) return;
                Difficulty d = sel.contains("Easy")   ? Difficulty.EASY
                             : sel.contains("Medium") ? Difficulty.MEDIUM
                             :                          Difficulty.HARD;
                gamePanel.setDifficulty(d);
                gamePanel.requestFocusInWindow();
            });
            newBtn    .addActionListener(e -> { gamePanel.generatePuzzle();       gamePanel.requestFocusInWindow(); });
            restartBtn.addActionListener(e -> { gamePanel.restartPuzzle();        gamePanel.requestFocusInWindow(); });
            solveBtn  .addActionListener(e -> { gamePanel.showSolution();         gamePanel.requestFocusInWindow(); });
            undoBtn   .addActionListener(e -> { gamePanel.undoLastMove();         gamePanel.requestFocusInWindow(); });
            btBtn     .addActionListener(e -> { gamePanel.solveByBacktracking();  gamePanel.requestFocusInWindow(); });

            JPanel container = new JPanel(new BorderLayout());
            container.add(topBar,    BorderLayout.NORTH);
            container.add(gamePanel, BorderLayout.CENTER);

            frame.getContentPane().add(container);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            gamePanel.requestFocusInWindow();
        });
    }
}