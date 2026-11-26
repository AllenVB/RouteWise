package com.routewise.depot;

import com.routewise.address.AddressValidationProperties;
import com.routewise.address.ServiceAreaRepository;
import com.routewise.address.ServiceAreaRepository.SnapResult;
import com.routewise.common.error.ApiException;
import com.routewise.common.error.ErrorCode;
import com.routewise.common.geo.Points;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The depot, guaranteed to be attached to the road graph.
 *
 * <p>The seed cannot store the depot's graph node: node ids only exist after the
 * data pipeline has loaded the road graph, which runs after the schema. So the
 * node is resolved on first use with the <b>same</b> snap query addresses use —
 * depot and delivery points must live in one node space, or the matrix would be
 * built over nodes that do not match.
 */
@Service
public class DepotService {

    private static final Logger log = LoggerFactory.getLogger(DepotService.class);

    private final DepotRepository depots;
    private final ServiceAreaRepository geo;
    private final AddressValidationProperties props;

    public DepotService(DepotRepository depots, ServiceAreaRepository geo,
                        AddressValidationProperties props) {
        this.depots = depots;
        this.geo = geo;
        this.props = props;
    }

    /** Scope is a single depot; with more, plans would have to name theirs. */
    @Transactional
    public Depot primary() {
        Depot depot = depots.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR,
                        "Tanımlı depo yok. Seed verisi yüklenmemiş."));
        if (depot.getSnappedNodeId() == null) {
            attachToGraph(depot);
        }
        return depot;
    }

    private void attachToGraph(Depot depot) {
        double lat = Points.latOf(depot.getLocation());
        double lon = Points.lonOf(depot.getLocation());
        SnapResult snap = geo.nearestRoadNode(lon, lat);
        if (snap == null) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "Yol grafı yüklenmemiş; depo yola bağlanamıyor. "
                            + "Veri hattını çalıştır: docker compose --profile tools run --rm dataprep all");
        }
        if (snap.distanceM() > props.maxSnapDistanceM()) {
            // Adres için engelleyici olan eşik depo için uyarıdır: depo seed'den
            // gelir, kullanıcı düzeltemez; rota yine de en yakın düğümden başlar.
            log.warn("depo '{}' en yakin yola {} m uzakta (esik {} m) — seed konumunu kontrol et",
                    depot.getName(), Math.round(snap.distanceM()), props.maxSnapDistanceM());
        }
        depot.snapTo(snap.nodeId());
        log.info("depo '{}' yol grafina baglandi: dugum {} ({} m)",
                depot.getName(), snap.nodeId(), Math.round(snap.distanceM()));
    }
}
