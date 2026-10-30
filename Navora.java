package com.navora.navora;
 
import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Sphere;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;
import javafx.util.Duration;
 
import java.io.InputStream;
import java.util.*;
 
/**
 * NAVORA - 3D campus explorer.
 *
 * All coordinates in the DATA sections are in "sketch pixels" taken from the
 * hand-drawn campus map (x to the right, y downwards, top of paper = north).
 * They are converted to 3D units by wx()/wz().
 *
 * To change the campus: edit buildGraph() (roads/walk nodes) and buildPlaces()
 * (buildings + rooms). Block photos go in src/main/resources/photos/
 * named like block1.jpg, block4.png, boyshostel1.jpg ...
 */
public class Navora extends Application {
 
    // ---------- scale / camera ----------
    private static final double S = 0.6;                 // sketch px -> 3D units
    private static final double CX = 1000, CY = 715;     // sketch centre
    private static final double METERS_PER_PX = 0.25;    // used for "~40 m" in directions
    private static final double FOV = 35, BASE_DIST = 1300;
    private static final double DEFAULT_TURN = 0, DEFAULT_TILT = 55;
 
    // ---------- model ----------
    private enum Kind { ACADEMIC, BOYS_HOSTEL, GIRLS_HOSTEL, FOOD, SERVICE, SPORT, SEATING, GATE }
 
    private record Room(String floor, String name, String keywords) {}
    private record Hit(Place place, String room, String label) {}
    private record Tag(Label label, double x, double y, double z, double minZoom) {}
    private record QItem(String id, double d) {}
 
    private static class Place {
        String name, alias = "";
        Kind kind;
        double px, py, w, d, doorX, doorY, height;
        int floors;
        char facing;
        String node;
        Color override;                  // real facade colour
        Color signColor;                 // colour of the name board above the door
        boolean pillars, glassStrip;     // facade details
        final List<Room> rooms = new ArrayList<>();
        PhongMaterial wall;
        Color base;
        Label tag;
 
        Place room(String floor, String roomName, String keywords) {
            rooms.add(new Room(floor, roomName, keywords));
            return this;
        }
 
        Place alias(String a)   { alias = a; return this; }
        Place color(String hex) { override = Color.web(hex); return this; }
        Place sign(String hex)  { signColor = Color.web(hex); return this; }
        Place pillars()         { pillars = true; return this; }
        Place strip()           { glassStrip = true; return this; }
        String title()          { return alias.isEmpty() ? name : name + " · " + alias; }
    }
 
    private static class Step {
        String text, turn;
        int dir;
        double len, lx, lz;
        final List<double[]> pts = new ArrayList<>();
    }
 
    // ---------- scene ----------
    private final Group world = new Group();
    private final Group routeGroup = new Group();
    private final Group markerGroup = new Group();
    private final Pane overlay = new Pane();
    private final Rotate turn = new Rotate(DEFAULT_TURN, Rotate.Y_AXIS);
    private final Rotate tilt = new Rotate(DEFAULT_TILT, Rotate.X_AXIS);
    private final Translate pan = new Translate(0, 0, 0);
    private SubScene campus;
    private PerspectiveCamera camera;
    private double zoom = 1.0;
    private double mouseX, mouseY;
 
    // ---------- data ----------
    private final Map<String, double[]> nodes = new LinkedHashMap<>();
    private final Map<String, List<String>> adj = new HashMap<>();
    private final List<Place> places = new ArrayList<>();
    private final List<Tag> tags = new ArrayList<>();
    private final Random rnd = new Random(11);
 
    // ---------- state ----------
    private Place selected, startPlace, routeDest;
    private final List<Step> steps = new ArrayList<>();
    private int stepIndex = 0;
    private double markerPx, markerPy;
    private List<Hit> currentHits = new ArrayList<>();
 
    // ---------- UI ----------
    private final Label info = new Label();
    private final Label panelTitle = new Label("Select a building");
    private final Label panelSub = new Label();
    private final Label photoHint = new Label();
    private final ImageView photoView = new ImageView();
    private final VBox roomsBox = new VBox(4);
    private final VBox stepsBox = new VBox(4);
    private final Label dirTitle = new Label();
    private final HBox navBar = new HBox(8);
    private final Button hereBtn = new Button("I'm here");
    private final Button goBtn = new Button("Navigate here");
    private final Button backBtn = new Button("‹ Back");
    private final Button nextBtn = new Button("Next ›");
    private final ListView<Hit> results = new ListView<>();
    private final ComboBox<String> myLoc = new ComboBox<>();
    private final TextField search = new TextField();
    private final Label youLabel = new Label("YOU ARE HERE");
    private Cylinder ring;
    private Sphere head;
 
    // ---------- materials ----------
    private final PhongMaterial grass = material("#8FC275");
    private final PhongMaterial roadMat = material("#6F7682");
    private final PhongMaterial stubMat = material("#CFCDC6");
    private final PhongMaterial gardenMat = material("#A7D98D");
    private final PhongMaterial roofMat = material("#B7C5D6");
    private final PhongMaterial glass = material("#83C9E8");
    private final PhongMaterial doorMat = material("#5B4636");
    private final PhongMaterial concrete = material("#E8E9EF");
    private final PhongMaterial treeGreen = material("#39885A");
    private final PhongMaterial trunk = material("#8C6347");
    private final PhongMaterial wood = material("#A8754E");
    private final PhongMaterial routeBlue = material("#2F80ED");
    private final PhongMaterial routeOrange = material("#FF8A00");
    private final PhongMaterial pinRed = material("#E5383B");
    private final PhongMaterial flagGreen = material("#1FA855");
 
    private static final String TAG_STYLE =
            "-fx-background-color: rgba(255,255,255,0.88); -fx-background-radius: 6;" +
            "-fx-padding: 1 5 1 5; -fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #203B55;";
    private static final String TAG_STYLE_SEL =
            "-fx-background-color: #FF9F43; -fx-background-radius: 6;" +
            "-fx-padding: 2 7 2 7; -fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;";
 
    // =====================================================================
    //  START
    // =====================================================================
    @Override
    public void start(Stage stage) {
        buildGraph();
        buildGround();
        buildRoads();
        buildGardens();
        buildPlaces();
        buildAvenueTrees();
        buildMarker();
        world.getChildren().addAll(routeGroup, markerGroup);
        world.getTransforms().setAll(pan, tilt, turn);
 
        AmbientLight ambient = new AmbientLight(Color.gray(0.45));
        PointLight sun = new PointLight(Color.gray(0.7));
        sun.setTranslateX(-300);
        sun.setTranslateY(-1100);
        sun.setTranslateZ(-1100);
        Group root3d = new Group(world, ambient, sun);
 
        campus = new SubScene(root3d, 1000, 650, true, SceneAntialiasing.BALANCED);
        campus.setFill(Color.web("#DDEAF4"));
        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(6000);
        camera.setFieldOfView(FOV);
        camera.setTranslateZ(-camDist());
        campus.setCamera(camera);
 
        overlay.setMouseTransparent(true);
        youLabel.setStyle("-fx-background-color: #E5383B; -fx-background-radius: 6; -fx-padding: 2 7 2 7;" +
                "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: white;");
        overlay.getChildren().add(youLabel);
 
        Pane viewport = new Pane(campus, overlay);
        viewport.setMinSize(500, 350);
        viewport.setStyle("-fx-background-color: #DDEAF4;");
        campus.widthProperty().bind(viewport.widthProperty());
        campus.heightProperty().bind(viewport.heightProperty());
        overlay.prefWidthProperty().bind(viewport.widthProperty());
        overlay.prefHeightProperty().bind(viewport.heightProperty());
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(viewport.widthProperty());
        clip.heightProperty().bind(viewport.heightProperty());
        viewport.setClip(clip);
 
        // ----- header -----
        Label title = new Label("NAVORA");
        title.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 25));
        title.setTextFill(Color.web("#203B55"));
        Label subtitle = new Label("YOUR CAMPUS, IN 3D");
        subtitle.setFont(Font.font("System", FontWeight.MEDIUM, 10));
        subtitle.setTextFill(Color.web("#73859A"));
        VBox branding = new VBox(3, title, subtitle);
 
        List<String> names = new ArrayList<>();
        for (Place p : places) names.add(p.name);
        myLoc.setItems(FXCollections.observableArrayList(names));
        myLoc.setPrefWidth(190);
        Label atLbl = new Label("You are at:");
        atLbl.setStyle("-fx-text-fill: #30465D; -fx-font-weight: bold;");
 
        search.setPromptText("Search Block 1, ATM, Moot Court, Gym...");
        search.setPrefWidth(300);
        search.setStyle("-fx-background-radius: 12; -fx-padding: 10; -fx-border-radius: 12; -fx-border-color: #D9E2EC;");
        Button find = new Button("Find");
        styleButton(find);
 
        HBox right = new HBox(10, atLbl, myLoc, search, find);
        right.setAlignment(Pos.CENTER_RIGHT);
        HBox header = new HBox(20, branding, right);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14, 20, 14, 20));
        header.setStyle("-fx-background-color: white;");
        HBox.setHgrow(branding, Priority.ALWAYS);
 
        // ----- right panel -----
        VBox panel = buildPanel();
        ScrollPane sp = new ScrollPane(panel);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setPrefWidth(350);
        sp.setMinWidth(350);
        sp.setStyle("-fx-background: white; -fx-background-color: white; -fx-border-color: #E3E9F0; -fx-border-width: 0 0 0 1;");
 
        // ----- footer -----
        info.setWrapText(true);
        info.setMaxWidth(Double.MAX_VALUE);
        info.setPadding(new Insets(10, 14, 10, 14));
        info.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-text-fill: #30465D; -fx-font-size: 13px;");
        HBox.setHgrow(info, Priority.ALWAYS);
        Button zoomIn = new Button("+");
        Button zoomOut = new Button("−");
        Button reset = new Button("Reset view");
        styleButton(zoomIn);
        styleButton(zoomOut);
        styleButton(reset);
        zoomIn.setOnAction(e -> changeZoom(1.15));
        zoomOut.setOnAction(e -> changeZoom(0.87));
        reset.setOnAction(e -> resetView());
        HBox footer = new HBox(10, info, zoomIn, zoomOut, reset);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(10, 16, 12, 16));
        footer.setStyle("-fx-background-color: #F5F7FB;");
 
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #F5F7FB;");
        root.setTop(header);
        root.setCenter(viewport);
        root.setRight(sp);
        root.setBottom(footer);
 
        // ----- behaviour -----
        find.setOnAction(e -> submitSearch());
        search.setOnAction(e -> submitSearch());
        search.textProperty().addListener((o, a, b) -> runSearch(b));
        results.getSelectionModel().selectedItemProperty().addListener((o, a, h) -> {
            if (h != null) selectPlace(h.place(), h.room());
        });
        hereBtn.setOnAction(e -> { if (selected != null) myLoc.setValue(selected.name); });
        goBtn.setOnAction(e -> navigateTo(selected));
        backBtn.setOnAction(e -> gotoStep(stepIndex - 1));
        nextBtn.setOnAction(e -> gotoStep(stepIndex + 1));
        myLoc.setOnAction(e -> {
            String n = myLoc.getValue();
            for (Place p : places) if (p.name.equals(n)) { setStart(p); break; }
        });
        addMouseControls(campus);
 
        Place gate = null;
        for (Place p : places) if (p.kind == Kind.GATE) gate = p;
        myLoc.setValue(gate.name);
        clearSelection();
        setStart(gate);
        resetView();
 
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                double t = now / 1e9;
                double pulse = (t % 1.6) / 1.6;
                ring.setScaleX(1 + pulse * 1.4);
                ring.setScaleZ(1 + pulse * 1.4);
                head.setTranslateY(-34 - 2.5 * Math.sin(t * 4));
                updateLabels();
            }
        }.start();
 
        Scene scene = new Scene(root, 1400, 850);
        stage.setTitle("Navora | 3D Campus Explorer");
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        stage.setScene(scene);
        stage.show();
    }
 
    // =====================================================================
    //  DATA: walk graph  (nodes sit on roads, buildings link to them)
    // =====================================================================
    private void buildGraph() {
        // main east-west road (y = 318)
        node("B7", 258, 318); node("ML", 345, 318); node("CH", 555, 318); node("ATM", 683, 318);
        node("GT", 945, 318); node("B6", 1232, 318); node("J6", 1365, 318); node("B5", 1427, 318);
        node("J7", 1610, 318);
        // gate + central avenue (x = 945)
        node("GATE", 945, 205); node("A1", 945, 395); node("W1", 748, 395); node("B1", 945, 648);
        node("SG", 945, 830); node("A3", 945, 940); node("B2", 945, 982); node("GH3", 945, 1180);
        // bottom road (y = 940) and right roads
        node("FC", 1192, 940); node("R4", 1365, 940);
        node("RV1", 1365, 395); node("TK", 1365, 635); node("RV2", 1610, 395);
        node("B4", 1610, 228); node("ST", 1610, 345); node("B3", 1610, 440);
        node("FC1", 1610, 580); node("X", 1610, 720);
        // left hostel road (x = 345)
        node("BH1", 345, 380); node("GH1", 345, 420); node("BH2", 345, 495);
        node("GYM", 345, 572); node("GH2", 345, 695); node("BH3", 345, 835);
 
        chain("B7", "ML", "CH", "ATM", "GT", "B6", "J6", "B5", "J7");
        chain("GATE", "GT", "A1", "B1", "SG", "A3", "B2", "GH3");
        chain("A1", "W1");
        chain("ML", "BH1", "GH1", "BH2", "GYM", "GH2", "BH3");
        chain("A3", "FC", "R4");
        chain("J6", "RV1", "TK", "R4");
        chain("RV1", "RV2");
        chain("B4", "J7", "ST", "RV2", "B3", "FC1", "X");
    }
 
    private void node(String id, double px, double py) {
        nodes.put(id, new double[]{px, py});
        adj.put(id, new ArrayList<>());
    }
 
    private void chain(String... ids) {
        for (int i = 0; i + 1 < ids.length; i++) {
            adj.get(ids[i]).add(ids[i + 1]);
            adj.get(ids[i + 1]).add(ids[i]);
        }
    }
 
    // =====================================================================
    //  DATA: buildings and rooms  (EDIT THE ROOMS TO MATCH YOUR REAL CAMPUS)
    // =====================================================================
    private void buildPlaces() {
        place("Main Gate", Kind.GATE, 945, 190, 100, 60, 0, ' ', "GATE")
                .room("Ground Floor", "Security Desk", "security guard entry exit");
 
        place("Chemist", Kind.SERVICE, 555, 250, 125, 90, 1, 'S', "CH")
                .room("Ground Floor", "Medical Store", "chemist medicine pharmacy");
        place("ATM", Kind.SERVICE, 683, 247, 120, 85, 1, 'S', "ATM")
                .room("Ground Floor", "ATM Machine", "cash bank money withdraw");
 
        // ---------- ACADEMIC BLOCKS (real names + facade look) ----------
        place("Block 1", Kind.ACADEMIC, 740, 648, 280, 255, 5, 'E', "B1")
                .alias("Alan Turing Block").color("#F7F7F5").sign("#D62828")
                .room("Ground Floor", "DSA Office", "dsa data structures algorithms")
                .room("Ground Floor", "DCC Office", "dcc")
                .room("Ground Floor", "Admission Cell", "admission enquiry")
                .room("Ground Floor", "Fee Counter", "fees payment cash accounts")
                .room("Ground Floor", "Digital Marketing Office", "digital marketing")
                .room("Ground Floor", "Conference Room", "meeting conference")
                .room("1st Floor", "Central Library", "library books reading study");
 
        place("Block 2", Kind.ACADEMIC, 735, 982, 270, 205, 7, 'E', "B2")
                .alias("Ratan Tata Block").color("#F2EBDD")
                .room("Ground Floor", "Canteen", "food eat lunch snacks")
                .room("Ground Floor", "Mini Easy Day", "easyday store shop grocery")
                .room("3rd Floor", "Department Library", "library books")
                .room("6th Floor", "Auditorium", "events stage seminar");
 
        place("Block 3", Kind.ACADEMIC, 1707, 440, 185, 125, 7, 'W', "B3")
                .alias("Ramanujan Block").color("#8A4B3C").pillars()
                .room("Ground Floor", "Happiness Centre", "happiness counselling wellbeing")
                .room("Ground Floor", "E-Cell", "ecell entrepreneurship startup")
                .room("Ground Floor", "Incubator Cell", "incubator startup")
                .room("Ground Floor", "International Affairs Office", "international affairs abroad foreign")
                .room("1st Floor", "Department Library", "library books")
                .room("6th Floor", "Studio Room", "studio recording video");
 
        place("Block 4", Kind.ACADEMIC, 1717, 228, 165, 95, 6, 'W', "B4")
                .alias("Dr. B.R. Ambedkar Block").color("#B5533C").strip()
                .room("Ground Floor", "Moot Court", "law court mock trial")
                .room("Ground Floor", "DCPD Office", "dcpd")
                .room("6th Floor", "Department Library", "library books")
                .room("6th Floor", "CSM Office", "csm");
 
        place("Block 5", Kind.ACADEMIC, 1427, 225, 115, 100, 5, 'S', "B5")
                .alias("Stella Block").color("#B85C45").pillars()
                .room("Ground Floor", "Dance Room", "dance cultural club")
                .room("Ground Floor", "Main Registrar Office", "registrar office admin")
                .room("1st Floor", "Department Library", "library books");
 
        place("Block 6", Kind.ACADEMIC, 1232, 222, 145, 95, 4, 'S', "B6")
                .alias("Swami Vivekananda Block").color("#C07A5E")
                .room("Ground Floor", "Dispensary", "medical doctor health medicine");
 
        place("Block 7", Kind.ACADEMIC, 258, 268, 195, 70, 5, 'S', "B7")
                .alias("Capgemini Block").color("#B8644A").pillars()
                .room("Ground Floor", "Capgemini Centre", "capgemini placement training")
                .room("1st Floor", "Department Library", "library books")
                .room("1st Floor", "Conference Hall", "meeting conference")
                .room("4th Floor", "ERP Cell", "erp");
 
        // ---------- HOSTELS, FOOD, SPORTS ----------
        place("Boys Hostel 1", Kind.BOYS_HOSTEL, 245, 392, 170, 95, 4, 'E', "BH1")
                .room("Ground Floor", "Warden Office", "warden").room("Ground Floor", "Common Room", "tv games");
        place("Girls Hostel 1", Kind.GIRLS_HOSTEL, 415, 405, 110, 110, 4, 'W', "GH1")
                .room("Ground Floor", "Warden Office", "warden").room("Ground Floor", "Common Room", "tv games");
        place("Boys Hostel 2", Kind.BOYS_HOSTEL, 250, 495, 130, 80, 4, 'E', "BH2")
                .room("Ground Floor", "Warden Office", "warden");
        place("Gym", Kind.SPORT, 418, 572, 125, 125, 1, 'W', "GYM")
                .room("Ground Floor", "Gym Hall", "fitness workout exercise");
        place("Girls Hostel 2", Kind.GIRLS_HOSTEL, 275, 695, 130, 100, 4, 'E', "GH2")
                .room("Ground Floor", "Warden Office", "warden");
        place("Boys Hostel 3", Kind.BOYS_HOSTEL, 275, 835, 130, 140, 4, 'E', "BH3")
                .room("Ground Floor", "Warden Office", "warden");
        place("Girls Hostel 3", Kind.GIRLS_HOSTEL, 1115, 1180, 290, 160, 4, 'W', "GH3")
                .room("Ground Floor", "Warden Office", "warden").room("Ground Floor", "Common Room", "tv games");
 
        place("Food Court 1", Kind.FOOD, 1192, 1020, 275, 110, 2, 'N', "FC")
                .room("Ground Floor", "Canteen", "food eat lunch snacks");
        place("Food Court 2", Kind.FOOD, 1747, 580, 185, 100, 2, 'W', "FC1")
                .room("Ground Floor", "Canteen", "food eat lunch snacks");
        place("Tuck Shop", Kind.FOOD, 1460, 635, 170, 110, 1, 'W', "TK")
                .room("Ground Floor", "Snacks Counter", "snacks tea coffee")
                .room("Ground Floor", "Stationery", "pen notebook");
 
        place("Seating", Kind.SEATING, 1727, 332, 135, 95, 0, 'W', "ST");
        place("Seating & Garden", Kind.SEATING, 735, 832, 270, 90, 0, 'E', "SG");
 
        // Build all 3D models AFTER every place is fully defined,
        // so colour / alias / facade options are applied.
        for (Place p : places) build3D(p);
    }
 
    private Place place(String name, Kind kind, double px, double py, double w, double d,
                        int floors, char facing, String node) {
        Place p = new Place();
        p.name = name; p.kind = kind; p.px = px; p.py = py; p.w = w; p.d = d;
        p.floors = floors; p.facing = facing; p.node = node;
        p.doorX = px;
        p.doorY = py;
        double hw = w * 0.47, hd = d * 0.47;
        switch (facing) {
            case 'E' -> p.doorX = px + hw;
            case 'W' -> p.doorX = px - hw;
            case 'S' -> p.doorY = py + hd;
            case 'N' -> p.doorY = py - hd;
            default -> { }
        }
        places.add(p);
        return p;
    }
 
    // =====================================================================
    //  3D BUILDERS
    // =====================================================================
    private void build3D(Place p) {
        Group g = new Group();
        double w = p.w * 0.94 * S, d = p.d * 0.94 * S;
        boolean side = p.facing == 'E' || p.facing == 'W';
        double lw = side ? d : w, ld = side ? w : d;   // local width/depth before rotation
 
        p.base = Color.web(switch (p.kind) {
            case ACADEMIC -> "#F0E7D8";
            case BOYS_HOSTEL -> "#CFE0F5";
            case GIRLS_HOSTEL -> "#F6D3E3";
            case FOOD -> "#FFE2B8";
            case SERVICE -> "#DAD9EC";
            case SPORT -> "#CDEBCB";
            case SEATING -> "#D8D6CF";
            case GATE -> "#6F8399";
        });
        if (p.override != null) p.base = p.override;
        p.wall = new PhongMaterial(p.base);
 
        if (p.kind == Kind.GATE) {
            double h = 50;
            Box l = new Box(5, h, 5);
            l.setMaterial(concrete);
            l.setTranslateX(-24);
            l.setTranslateY(-h / 2);
            Box r = new Box(5, h, 5);
            r.setMaterial(concrete);
            r.setTranslateX(24);
            r.setTranslateY(-h / 2);
            Box beam = new Box(58, 7, 7);
            beam.setMaterial(p.wall);
            beam.setTranslateY(-h + 3);
            g.getChildren().addAll(l, r, beam);
            p.height = h + 4;
        } else if (p.kind == Kind.SEATING) {
            Box pad = new Box(lw, 3, ld);
            pad.setMaterial(p.wall);
            pad.setTranslateY(-1.5);
            g.getChildren().add(pad);
            for (int i = 0; i < 3; i++) {
                Box seat = new Box(12, 2, 4);
                seat.setMaterial(wood);
                seat.setTranslateX((i - 1) * lw / 3.2);
                seat.setTranslateY(-5);
                Box back = new Box(12, 4, 1);
                back.setMaterial(wood);
                back.setTranslateX((i - 1) * lw / 3.2);
                back.setTranslateY(-8);
                back.setTranslateZ(2);
                g.getChildren().addAll(seat, back);
            }
            p.height = 3;
        } else {
            double h = 10 + 11 * p.floors;
            Box base = new Box(lw, h, ld);
            base.setMaterial(p.wall);
            base.setTranslateY(-h / 2);
            Box roofBox = new Box(lw + 3, 3, ld + 3);
            roofBox.setMaterial(roofMat);
            roofBox.setTranslateY(-h - 1.5);
            g.getChildren().addAll(base, roofBox);
 
            int per = Math.max(2, (int) (lw / 17));
            for (int f = 0; f < p.floors; f++) {
                for (int i = 0; i < per; i++) {
                    double wxl = -lw / 2 + (i + 0.5) * lw / per;
                    if (f == 0 && Math.abs(wxl) < 8) continue;
                    Box win = new Box(5.5, 5, 1.2);
                    win.setMaterial(glass);
                    win.setTranslateX(wxl);
                    win.setTranslateY(-(9 + 11 * f));
                    win.setTranslateZ(-ld / 2 - 0.4);
                    g.getChildren().add(win);
                }
            }
            Box door = new Box(8, 9, 1.4);
            door.setMaterial(doorMat);
            door.setTranslateY(-4.5);
            door.setTranslateZ(-ld / 2 - 0.5);
            g.getChildren().add(door);
 
            // name board above the entrance (red LED for Block 1, dark for the rest)
            if (p.kind == Kind.ACADEMIC) {
                Box sign = new Box(Math.min(lw * 0.6, 40), 3.2, 1.2);
                sign.setMaterial(new PhongMaterial(p.signColor != null ? p.signColor : Color.web("#2B2B2B")));
                sign.setTranslateY(-13);
                sign.setTranslateZ(-ld / 2 - 0.7);
                g.getChildren().add(sign);
            }
 
            if (p.pillars) {                       // brick pillars between window columns
                PhongMaterial pm = new PhongMaterial(p.base.darker());
                for (int i = 0; i <= per; i++) {
                    Box pil = new Box(2.5, h, 2);
                    pil.setMaterial(pm);
                    pil.setTranslateX(-lw / 2 + i * lw / per);
                    pil.setTranslateY(-h / 2);
                    pil.setTranslateZ(-ld / 2 - 0.6);
                    g.getChildren().add(pil);
                }
            }
            if (p.glassStrip) {                    // tall glass strip like Ambedkar Block
                Box strip = new Box(lw * 0.2, h * 0.8, 1.4);
                strip.setMaterial(glass);
                strip.setTranslateY(-h * 0.5);
                strip.setTranslateZ(-ld / 2 - 0.5);
                g.getChildren().add(strip);
            }
            p.height = h + 3;
        }
 
        double yaw = switch (p.facing) {
            case 'E' -> -90;
            case 'W' -> 90;
            case 'N' -> 180;
            default -> 0;
        };
        g.getTransforms().add(new Rotate(yaw, Rotate.Y_AXIS));
        g.setTranslateX(wx(p.px));
        g.setTranslateZ(wz(p.py));
        g.setCursor(Cursor.HAND);
        g.setOnMouseClicked(e -> {
            if (e.isStillSincePress()) selectPlace(p, null);
            e.consume();
        });
        world.getChildren().add(g);
 
        if (p.node != null) {
            double[] n = nodes.get(p.node);
            addSeg(world, p.doorX, p.doorY, n[0], n[1], 7, 1.0, stubMat, -0.5);
        }
 
        double minZoom = p.kind == Kind.SEATING ? 1.3 : 0;
        p.tag = addTag(p.name.toUpperCase(), wx(p.px), -p.height - 8, wz(p.py), minZoom);
    }
 
    private Label addTag(String text, double x, double y, double z, double minZoom) {
        Label l = new Label(text);
        l.setStyle(TAG_STYLE);
        overlay.getChildren().add(l);
        tags.add(new Tag(l, x, y, z, minZoom));
        return l;
    }
 
    private void buildGround() {
        Box ground = new Box(1760 * S, 2, 1190 * S);
        ground.setMaterial(grass);
        ground.setTranslateY(1);
        world.getChildren().add(ground);
    }
 
    private void buildRoads() {
        addRoad(258, 318, 1610, 318, 30);     // main road
        addRoad(945, 200, 945, 1180, 44);     // central avenue from the gate
        addRoad(345, 318, 345, 835, 22);      // hostel road
        addRoad(565, 395, 945, 395, 24);      // road north of Block 1 garden
        addRoad(945, 940, 1365, 940, 24);     // bottom road
        addRoad(1365, 318, 1365, 940, 26);    // right-side vertical road
        addRoad(1365, 395, 1610, 395, 24);    // road beside the right garden
        addRoad(1610, 175, 1610, 720, 28);    // road beside Block 4 / Block 3
    }
 
    private void addRoad(double x1, double y1, double x2, double y2, double thPx) {
        boolean horizontal = y1 == y2;
        double w = (horizontal ? Math.abs(x2 - x1) + thPx : thPx) * S;
        double d = (horizontal ? thPx : Math.abs(y2 - y1) + thPx) * S;
        Box r = new Box(w, 1.6, d);
        r.setMaterial(roadMat);
        r.setTranslateX(wx((x1 + x2) / 2));
        r.setTranslateZ(wz((y1 + y2) / 2));
        r.setTranslateY(-0.8);
        world.getChildren().add(r);
    }
 
    private void buildGardens() {
        garden("CENTRAL GARDEN", 1030, 360, 1350, 800, 9, 0);
        garden("GARDEN", 590, 405, 905, 520, 4, 1.3);
        garden("GARDEN", 1055, 815, 1305, 890, 3, 1.3);
        garden("GARDEN", 1430, 395, 1580, 510, 3, 1.3);
        garden("GARDEN", 1485, 175, 1585, 290, 3, 1.3);
    }
 
    private void garden(String name, double x1, double y1, double x2, double y2, int trees, double minZoom) {
        Box lawn = new Box((x2 - x1) * S, 1.2, (y2 - y1) * S);
        lawn.setMaterial(gardenMat);
        lawn.setTranslateX(wx((x1 + x2) / 2));
        lawn.setTranslateZ(wz((y1 + y2) / 2));
        lawn.setTranslateY(-0.6);
        world.getChildren().add(lawn);
        addTag(name, wx((x1 + x2) / 2), -6, wz((y1 + y2) / 2), minZoom);
        for (int i = 0; i < trees; i++) {
            double tx = x1 + 20 + rnd.nextDouble() * (x2 - x1 - 40);
            double ty = y1 + 20 + rnd.nextDouble() * (y2 - y1 - 40);
            addTree(tx, ty);
        }
    }
 
    private void buildAvenueTrees() {
        double[] ys = {440, 520, 600, 730, 880};
        for (double y : ys) {
            addTree(905, y);
            addTree(985, y);
        }
    }
 
    private void addTree(double px, double py) {
        Cylinder stem = new Cylinder(1.6, 9);
        stem.setMaterial(trunk);
        stem.setTranslateX(wx(px));
        stem.setTranslateZ(wz(py));
        stem.setTranslateY(-4.5);
        Sphere leaves = new Sphere(6.5);
        leaves.setMaterial(treeGreen);
        leaves.setTranslateX(wx(px));
        leaves.setTranslateZ(wz(py));
        leaves.setTranslateY(-14);
        world.getChildren().addAll(stem, leaves);
    }
 
    private void buildMarker() {
        Cylinder pole = new Cylinder(1.2, 30);
        pole.setMaterial(pinRed);
        pole.setTranslateY(-15);
        head = new Sphere(7);
        head.setMaterial(pinRed);
        head.setTranslateY(-34);
        ring = new Cylinder(13, 0.8);
        ring.setMaterial(routeOrange);
        ring.setTranslateY(-2.6);
        markerGroup.getChildren().addAll(ring, pole, head);
    }
 
    private void moveMarker(double px, double py) {
        markerPx = px;
        markerPy = py;
        markerGroup.setTranslateX(wx(px));
        markerGroup.setTranslateZ(wz(py));
    }
 
    /** Draws a flat strip between two sketch-pixel points. */
    private void addSeg(Group target, double x1p, double y1p, double x2p, double y2p,
                        double width, double height, PhongMaterial m, double yCenter) {
        double x1 = wx(x1p), z1 = wz(y1p), x2 = wx(x2p), z2 = wz(y2p);
        double dx = x2 - x1, dz = z2 - z1;
        double len = Math.hypot(dx, dz);
        if (len < 0.5) return;
        Box b = new Box(len, height, width);
        b.setMaterial(m);
        b.setTranslateX((x1 + x2) / 2);
        b.setTranslateZ((z1 + z2) / 2);
        b.setTranslateY(yCenter);
        b.getTransforms().add(new Rotate(Math.toDegrees(Math.atan2(-dz, dx)), Rotate.Y_AXIS));
        target.getChildren().add(b);
    }
 
    // =====================================================================
    //  RIGHT PANEL
    // =====================================================================
    private VBox buildPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color: white;");
 
        results.setPrefHeight(150);
        results.setVisible(false);
        results.setManaged(false);
        results.setCellFactory(lv -> new ListCell<Hit>() {
            @Override
            protected void updateItem(Hit h, boolean empty) {
                super.updateItem(h, empty);
                setText(empty || h == null ? null : h.label());
            }
        });
 
        panelTitle.setFont(Font.font("System", FontWeight.EXTRA_BOLD, 22));
        panelTitle.setTextFill(Color.web("#203B55"));
        panelTitle.setWrapText(true);
        panelSub.setTextFill(Color.web("#73859A"));
        panelSub.setWrapText(true);
 
        photoView.setFitWidth(316);
        photoView.setFitHeight(210);
        photoView.setPreserveRatio(true);
        photoView.setSmooth(true);
        photoHint.setWrapText(true);
        photoHint.setStyle("-fx-text-fill: #8A9BAD; -fx-font-size: 11px;");
        photoHint.setMaxWidth(290);
        StackPane photoBox = new StackPane(photoHint, photoView);
        photoBox.setMinHeight(110);
        photoBox.setStyle("-fx-background-color: #EEF2F7; -fx-background-radius: 12;");
 
        Label roomsTitle = sectionTitle("Inside this building");
 
        styleButton(hereBtn);
        styleButton(goBtn);
        goBtn.setStyle(goBtn.getStyle().replace("#244A68", "#E8590C"));
        HBox actions = new HBox(8, hereBtn, goBtn);
 
        dirTitle.setFont(Font.font("System", FontWeight.BOLD, 14));
        dirTitle.setTextFill(Color.web("#203B55"));
        dirTitle.setWrapText(true);
        styleButton(backBtn);
        styleButton(nextBtn);
        Button clear = new Button("Clear route");
        styleButton(clear);
        clear.setOnAction(e -> {
            clearRoute();
            if (startPlace != null) moveMarker(startPlace.doorX, startPlace.doorY);
            info.setText("Route cleared.");
        });
        navBar.getChildren().addAll(backBtn, nextBtn, clear);
        navBar.setVisible(false);
        navBar.setManaged(false);
 
        box.getChildren().addAll(results, panelTitle, panelSub, photoBox, roomsTitle, roomsBox,
                actions, dirTitle, stepsBox, navBar);
        return box;
    }
 
    private Label sectionTitle(String t) {
        Label l = new Label(t.toUpperCase());
        l.setStyle("-fx-text-fill: #73859A; -fx-font-size: 11px; -fx-font-weight: bold;");
        return l;
    }
 
    // =====================================================================
    //  SELECTION / PANEL
    // =====================================================================
    private void selectPlace(Place p, String highlightRoom) {
        if (selected != null) setHighlight(selected, false);
        selected = p;
        setHighlight(p, true);
 
        panelTitle.setText(p.title());
        panelSub.setText(kindLabel(p.kind) + (p.floors > 0 ? " · " + p.floors + (p.floors == 1 ? " floor" : " floors") : ""));
        loadPhoto(p);
 
        roomsBox.getChildren().clear();
        if (p.rooms.isEmpty()) {
            Label none = new Label("Nothing listed here yet.");
            none.setStyle("-fx-text-fill: #8A9BAD;");
            roomsBox.getChildren().add(none);
        } else {
            Map<String, List<Room>> byFloor = new LinkedHashMap<>();
            for (Room r : p.rooms) byFloor.computeIfAbsent(r.floor(), k -> new ArrayList<>()).add(r);
            for (Map.Entry<String, List<Room>> en : byFloor.entrySet()) {
                Label fl = new Label(en.getKey());
                fl.setStyle("-fx-font-weight: bold; -fx-text-fill: #244A68; -fx-padding: 6 0 0 0;");
                roomsBox.getChildren().add(fl);
                for (Room r : en.getValue()) {
                    Label rl = new Label("•  " + r.name());
                    rl.setWrapText(true);
                    boolean hit = highlightRoom != null && highlightRoom.equals(r.name());
                    rl.setStyle(hit
                            ? "-fx-background-color: #FFE3C2; -fx-background-radius: 6; -fx-padding: 3 8 3 8; -fx-font-weight: bold; -fx-text-fill: #A34700;"
                            : "-fx-text-fill: #30465D; -fx-padding: 0 0 0 8;");
                    roomsBox.getChildren().add(rl);
                }
            }
        }
        hereBtn.setDisable(false);
        goBtn.setDisable(false);
 
        String where = highlightRoom == null ? p.title() : highlightRoom + " is in " + p.title();
        info.setText(where + ". Press \"Navigate here\" for directions from " +
                (startPlace == null ? "your location" : startPlace.name) + ".");
        focusOn(p.px, p.py);
    }
 
    private void clearSelection() {
        if (selected != null) setHighlight(selected, false);
        selected = null;
        panelTitle.setText("Select a building");
        panelSub.setText("Click any block, or search a room (for example \"moot court\").");
        roomsBox.getChildren().clear();
        photoView.setImage(null);
        photoHint.setText("");
        hereBtn.setDisable(true);
        goBtn.setDisable(true);
        if (routeDest == null) {
            info.setText("Welcome to Navora · Drag to rotate · Scroll to zoom · Shift/right-drag to pan");
        }
    }
 
    private void setHighlight(Place p, boolean on) {
        p.wall.setDiffuseColor(on ? Color.web("#FF9F43") : p.base);
        p.tag.setStyle(on ? TAG_STYLE_SEL : TAG_STYLE);
    }
 
    /** Loads /photos/<name without spaces>.jpg|jpeg|png, e.g. "Block 1" -> block1.jpg */
    private void loadPhoto(Place p) {
        String id = p.name.toLowerCase().replaceAll("[^a-z0-9]+", "");
        Image img = null;
        for (String ext : new String[]{".jpg", ".jpeg", ".png"}) {
            try (InputStream in = Navora.class.getResourceAsStream("/photos/" + id + ext)) {
                if (in != null) {
                    img = new Image(in);
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        photoView.setImage(img);
        photoView.setVisible(img != null);
        photoHint.setVisible(img == null);
        photoHint.setText(img == null
                ? "Photo coming soon.\nAdd src/main/resources/photos/" + id + ".jpg"
                : "");
    }
 
    private static String kindLabel(Kind k) {
        return switch (k) {
            case ACADEMIC -> "Academic block";
            case BOYS_HOSTEL -> "Boys hostel";
            case GIRLS_HOSTEL -> "Girls hostel";
            case FOOD -> "Food";
            case SERVICE -> "Campus service";
            case SPORT -> "Sports";
            case SEATING -> "Seating area";
            case GATE -> "Entrance";
        };
    }
 
    // =====================================================================
    //  SEARCH
    // =====================================================================
    private void submitSearch() {
        runSearch(search.getText());
        if (!currentHits.isEmpty()) {
            Hit h = currentHits.get(0);
            selectPlace(h.place(), h.room());
        }
    }
 
    private void runSearch(String q) {
        currentHits = new ArrayList<>();
        if (q == null || q.isBlank()) {
            results.getItems().clear();
            results.setVisible(false);
            results.setManaged(false);
            return;
        }
        String[] tokens = q.trim().toLowerCase().split("\\s+");
        for (Place p : places) {
            String ph = (p.name + " " + p.alias + " " + kindLabel(p.kind)).toLowerCase();
            if (matches(ph, tokens)) currentHits.add(new Hit(p, null, p.title() + " · " + kindLabel(p.kind)));
            for (Room r : p.rooms) {
                String h = (r.name() + " " + r.keywords() + " " + p.name + " " + p.alias + " " + r.floor()).toLowerCase();
                if (matches(h, tokens)) currentHits.add(new Hit(p, r.name(), r.name() + "  —  " + p.title() + " · " + r.floor()));
                if (currentHits.size() >= 14) break;
            }
            if (currentHits.size() >= 14) break;
        }
        results.getItems().setAll(currentHits);
        results.setVisible(true);
        results.setManaged(true);
        if (currentHits.isEmpty()) {
            info.setText("No match for \"" + q + "\". Try a block, hostel, ATM, gym, library or food court.");
        }
    }
 
    private boolean matches(String hay, String[] tokens) {
        for (String t : tokens) if (!hay.contains(t)) return false;
        return true;
    }
 
    // =====================================================================
    //  ROUTING
    // =====================================================================
    private void setStart(Place p) {
        startPlace = p;
        clearRoute();
        moveMarker(p.doorX, p.doorY);
        info.setText("You are at " + p.name + ". Search or click a destination.");
    }
 
    private void clearRoute() {
        routeGroup.getChildren().clear();
        steps.clear();
        stepsBox.getChildren().clear();
        routeDest = null;
        stepIndex = 0;
        dirTitle.setText("");
        navBar.setVisible(false);
        navBar.setManaged(false);
    }
 
    private void navigateTo(Place dest) {
        if (dest == null || startPlace == null) return;
        if (dest == startPlace) {
            info.setText("You are already at " + dest.name + ".");
            return;
        }
        List<String> ids = shortestPath(startPlace.node, dest.node);
        if (ids == null) {
            info.setText("No walkable route found to " + dest.name + ".");
            return;
        }
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{startPlace.doorX, startPlace.doorY});
        for (String id : ids) pts.add(nodes.get(id));
        pts.add(new double[]{dest.doorX, dest.doorY});
 
        steps.clear();
        Step cur = null;
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get(i + 1);
            double dx = b[0] - a[0], dz = -(b[1] - a[1]);
            double len = Math.hypot(dx, dz);
            if (len < 1e-6) continue;
            int dir = compassIndex(dx, dz);
            if (cur != null && (dir == cur.dir || len < 30)) {
                cur.len += len;
                cur.pts.add(b);
                cur.lx = dx;
                cur.lz = dz;
            } else {
                Step s = new Step();
                s.dir = dir;
                s.len = len;
                s.lx = dx;
                s.lz = dz;
                s.pts.add(a);
                s.pts.add(b);
                if (cur == null) {
                    s.turn = "Head";
                } else {
                    double cross = cur.lx * dz - cur.lz * dx;
                    double dot = cur.lx * dx + cur.lz * dz;
                    double ang = Math.toDegrees(Math.atan2(cross, dot));
                    s.turn = Math.abs(ang) < 25 ? "Continue"
                            : Math.abs(ang) > 135 ? "Turn around"
                            : ang > 0 ? "Turn left" : "Turn right";
                }
                steps.add(s);
                cur = s;
            }
        }
        String[] dirs = {"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"};
        for (Step s : steps) {
            long m = Math.max(5L, Math.round(s.len * METERS_PER_PX / 5.0) * 5);
            s.text = switch (s.turn) {
                case "Head" -> "Head " + dirs[s.dir] + " for ~" + m + " m";
                case "Continue" -> "Continue " + dirs[s.dir] + " for ~" + m + " m";
                default -> s.turn + " and walk " + dirs[s.dir] + " for ~" + m + " m";
            };
        }
        Step arrive = new Step();
        arrive.text = "You have arrived at " + dest.name;
        arrive.pts.add(new double[]{dest.doorX, dest.doorY});
        steps.add(arrive);
 
        routeDest = dest;
        stepIndex = 0;
        dirTitle.setText("Directions: " + startPlace.name + " → " + dest.name);
        navBar.setVisible(true);
        navBar.setManaged(true);
        moveMarker(startPlace.doorX, startPlace.doorY);
        drawRoute();
        refreshSteps();
        focusOn((startPlace.px + dest.px) / 2, (startPlace.py + dest.py) / 2);
        info.setText(steps.get(0).text + "   (use Next to walk through the route)");
    }
 
    private void gotoStep(int i) {
        if (steps.isEmpty()) return;
        stepIndex = Math.max(0, Math.min(steps.size() - 1, i));
        double[] start = steps.get(stepIndex).pts.get(0);
        moveMarker(start[0], start[1]);
        drawRoute();
        refreshSteps();
        info.setText(steps.get(stepIndex).text);
    }
 
    private void drawRoute() {
        routeGroup.getChildren().clear();
        for (int si = 0; si < steps.size(); si++) {
            Step s = steps.get(si);
            PhongMaterial m = si == stepIndex ? routeOrange : routeBlue;
            for (int j = 0; j + 1 < s.pts.size(); j++) {
                double[] a = s.pts.get(j), b = s.pts.get(j + 1);
                addSeg(routeGroup, a[0], a[1], b[0], b[1], 6.5, 2.2, m, -2.7);
            }
            for (double[] pt : s.pts) {
                if (s.pts.size() < 2) break;
                Sphere dot = new Sphere(3.4);
                dot.setMaterial(m);
                dot.setTranslateX(wx(pt[0]));
                dot.setTranslateZ(wz(pt[1]));
                dot.setTranslateY(-2.7);
                routeGroup.getChildren().add(dot);
            }
        }
        if (routeDest != null) {
            Cylinder pole = new Cylinder(1, 26);
            pole.setMaterial(flagGreen);
            pole.setTranslateY(-13);
            Sphere flag = new Sphere(6);
            flag.setMaterial(flagGreen);
            flag.setTranslateY(-29);
            Group g = new Group(pole, flag);
            g.setTranslateX(wx(routeDest.doorX));
            g.setTranslateZ(wz(routeDest.doorY));
            routeGroup.getChildren().add(g);
        }
    }
 
    private void refreshSteps() {
        stepsBox.getChildren().clear();
        for (int i = 0; i < steps.size(); i++) {
            Label l = new Label((i + 1) + ".  " + steps.get(i).text);
            l.setWrapText(true);
            l.setMaxWidth(310);
            l.setStyle(i == stepIndex
                    ? "-fx-background-color: #FFE3C2; -fx-background-radius: 8; -fx-padding: 6 10 6 10; -fx-font-weight: bold; -fx-text-fill: #A34700;"
                    : "-fx-text-fill: #30465D; -fx-padding: 4 10 4 10;");
            stepsBox.getChildren().add(l);
        }
        backBtn.setDisable(stepIndex == 0);
        nextBtn.setDisable(stepIndex >= steps.size() - 1);
    }
 
    private List<String> shortestPath(String from, String to) {
        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        PriorityQueue<QItem> pq = new PriorityQueue<>(Comparator.comparingDouble(QItem::d));
        dist.put(from, 0.0);
        pq.add(new QItem(from, 0));
        while (!pq.isEmpty()) {
            QItem q = pq.poll();
            if (q.d() > dist.getOrDefault(q.id(), Double.MAX_VALUE)) continue;
            if (q.id().equals(to)) break;
            for (String n : adj.getOrDefault(q.id(), new ArrayList<>())) {
                double[] a = nodes.get(q.id()), b = nodes.get(n);
                double nd = q.d() + Math.hypot(a[0] - b[0], a[1] - b[1]);
                if (nd < dist.getOrDefault(n, Double.MAX_VALUE)) {
                    dist.put(n, nd);
                    prev.put(n, q.id());
                    pq.add(new QItem(n, nd));
                }
            }
        }
        if (!dist.containsKey(to)) return null;
        LinkedList<String> path = new LinkedList<>();
        for (String at = to; at != null; at = prev.get(at)) path.addFirst(at);
        return path;
    }
 
    /** 0=E 1=NE 2=N 3=NW 4=W 5=SW 6=S 7=SE (dz positive = north). */
    private int compassIndex(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        int idx = (int) Math.round(angle / 45.0);
        if (idx < 0) idx += 8;
        return idx % 8;
    }
 
    // =====================================================================
    //  CAMERA / PROJECTION / LABELS
    // =====================================================================
    private double wx(double px) { return (px - CX) * S; }
 
    private double wz(double py) { return (CY - py) * S; }
 
    private double camDist() { return BASE_DIST / zoom; }
 
    private double focal() { return (campus.getHeight() / 2.0) / Math.tan(Math.toRadians(FOV / 2.0)); }
 
    /** Projects a world point to viewport pixels (same maths as the 3D camera). */
    private Point2D project(double x, double y, double z) {
        double a = Math.toRadians(turn.getAngle()), t = Math.toRadians(tilt.getAngle());
        double x1 = x * Math.cos(a) + z * Math.sin(a);
        double z1 = -x * Math.sin(a) + z * Math.cos(a);
        double y2 = y * Math.cos(t) - z1 * Math.sin(t);
        double z2 = y * Math.sin(t) + z1 * Math.cos(t);
        double x2 = x1 + pan.getX();
        y2 += pan.getY();
        double dz = z2 + camDist();
        if (dz < 5) return null;
        double f = focal();
        return new Point2D(campus.getWidth() / 2.0 + f * x2 / dz, campus.getHeight() / 2.0 + f * y2 / dz);
    }
 
    private void updateLabels() {
        for (Tag t : tags) {
            Point2D p = project(t.x(), t.y(), t.z());
            boolean show = p != null && (zoom >= t.minZoom() || t.label() == (selected == null ? null : selected.tag));
            t.label().setVisible(show);
            if (show) {
                t.label().setLayoutX(p.getX() - t.label().getWidth() / 2);
                t.label().setLayoutY(p.getY() - t.label().getHeight());
            }
        }
        Point2D m = project(wx(markerPx), -52, wz(markerPy));
        youLabel.setVisible(m != null);
        if (m != null) {
            youLabel.setLayoutX(m.getX() - youLabel.getWidth() / 2);
            youLabel.setLayoutY(m.getY() - youLabel.getHeight());
        }
    }
 
    private void focusOn(double px, double py) {
        double x = wx(px), z = wz(py);
        double a = Math.toRadians(turn.getAngle()), t = Math.toRadians(tilt.getAngle());
        double x1 = x * Math.cos(a) + z * Math.sin(a);
        double z1 = -x * Math.sin(a) + z * Math.cos(a);
        double y2 = -z1 * Math.sin(t);
        new Timeline(new KeyFrame(Duration.millis(450),
                new KeyValue(pan.xProperty(), -x1),
                new KeyValue(pan.yProperty(), -y2))).play();
    }
 
    private void addMouseControls(SubScene view) {
        view.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();
        });
        view.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            double dx = e.getSceneX() - mouseX, dy = e.getSceneY() - mouseY;
            if (e.getButton() == MouseButton.SECONDARY || e.isShiftDown()) {
                double k = camDist() / focal();
                pan.setX(pan.getX() + dx * k);
                pan.setY(pan.getY() + dy * k);
            } else if (e.getButton() == MouseButton.PRIMARY) {
                turn.setAngle(turn.getAngle() + dx * 0.3);
                tilt.setAngle(Math.max(25, Math.min(85, tilt.getAngle() + dy * 0.2)));
            }
            mouseX = e.getSceneX();
            mouseY = e.getSceneY();
        });
        view.addEventHandler(ScrollEvent.SCROLL, e -> {
            changeZoom(e.getDeltaY() > 0 ? 1.1 : 0.9);
            e.consume();
        });
        // click on empty ground (buildings consume their own clicks)
        view.setOnMouseClicked(e -> {
            if (e.isStillSincePress()) clearSelection();
        });
    }
 
    private void changeZoom(double factor) {
        zoom = Math.max(0.5, Math.min(3.5, zoom * factor));
        camera.setTranslateZ(-camDist());
    }
 
    private void resetView() {
        turn.setAngle(DEFAULT_TURN);
        tilt.setAngle(DEFAULT_TILT);
        zoom = 1.0;
        pan.setX(0);
        pan.setY(0);
        camera.setTranslateZ(-camDist());
    }
 
    // =====================================================================
    //  HELPERS
    // =====================================================================
    private static PhongMaterial material(String color) {
        PhongMaterial m = new PhongMaterial();
        m.setDiffuseColor(Color.web(color));
        return m;
    }
 
    private void styleButton(Button b) {
        b.setStyle("-fx-background-color: #244A68; -fx-text-fill: white; -fx-font-weight: bold;" +
                "-fx-background-radius: 10; -fx-padding: 9 15 9 15; -fx-cursor: hand;");
    }
 
    public static void main(String[] args) {
        launch(args);
    }
}
 
