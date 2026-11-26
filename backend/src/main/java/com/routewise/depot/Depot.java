package com.routewise.depot;

import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.locationtech.jts.geom.Point;

/** Distribution centre. Schema owned by Liquibase; seeded, no management screen. */
@Entity
@Table(name = "depots")
public class Depot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "address_text")
    private String addressText;

    @Column(columnDefinition = "geometry(Point,4326)", nullable = false)
    private Point location;

    /** Every route starts and ends here, so it must be a node of the road graph. */
    @Column(name = "snapped_node_id")
    private Long snappedNodeId;

    @Column(name = "opening_time", nullable = false)
    private LocalTime openingTime;

    @Column(name = "closing_time", nullable = false)
    private LocalTime closingTime;

    @Column(name = "max_route_duration_min", nullable = false)
    private Integer maxRouteDurationMin;

    protected Depot() {}

    /** Visible for tests; production depots come from the seed. */
    Depot(Long id, String name, Point location, Long snappedNodeId) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.snappedNodeId = snappedNodeId;
        this.openingTime = LocalTime.of(8, 0);
        this.closingTime = LocalTime.of(18, 0);
        this.maxRouteDurationMin = 540;
    }

    void snapTo(long nodeId) {
        this.snappedNodeId = nodeId;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getAddressText() { return addressText; }
    public Point getLocation() { return location; }
    public Long getSnappedNodeId() { return snappedNodeId; }
    public LocalTime getOpeningTime() { return openingTime; }
    public LocalTime getClosingTime() { return closingTime; }
    public Integer getMaxRouteDurationMin() { return maxRouteDurationMin; }
}
