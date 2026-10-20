# 29-19 Cost Checkpoint: the measured estate cost against the ceiling

**Measured:** 2026-10-20, from the subscription's own Cost Management data (ActualCost, daily, grouped by meter and
resource), queried at 10:30Z. Every figure below traces to a row of `evidence/29-19-cost.txt`, which keeps the query
body and the raw JSON verbatim.

**Window.** The estate's first resources were created 2026-10-19T00:41Z. Cost Management lags by about 10 hours, so
day 2026-10-19 holds the estate from 00:41Z and day 2026-10-20 is **INCOMPLETE** (a few hours posted). The two days
are summed, and every projection is a unit rate taken over at least 24 hours of posted usage: PG compute 25.000 h,
ingress IP 26.301 h, outbound IP 27.301 h, nodes 94.983 node-hours. Lines priced by quantity rather than by the hour
(storage, disks, operations) are spread over the window of their own resource: 25.000 h for PG storage (its compute
hours) and 26.301 h (the ingress IP's hours, the estate's wall-clock) for the rest.

**Projection.** £/month = unit rate × 730.08 h (30.42 days); £/day = £/month ÷ 30.42. Nodes are projected at the
live count: `az aks show` reads nodepool1 count 4 Standard_B2s, sku Free; node 3 (D-34) joined 2026-10-19T10:48:10Z.

## The four meters research could not settle

| Meter | Measured in the window | £/day | £/month |
|---|---|---|---|
| FreeTierInfrastructureCost (AKS Uptime SLA, £0.0377/h per research) | no line in the usage data for jtoye-stg-aks | £0.00 | £0.00 (no line in the usage data) |
| Load Balancer (`Standard Included LB Rules and Outbound Rules - Free`, resource `kubernetes`) | 27.285 units, £0.0000 | £0.00 | £0.00 (still on the free meter) |
| B2s nodes (`B2s`, aks-nodepool1-24453234-vmss) | 94.983 node-h, £3.3837 = £0.035624/node-h | £3.42 | £104.03 (× 4 nodes) |
| Static Public IP (`Standard IPv4 Static Public IP`, ingress + outbound) | 26.301 h £0.0993 + 27.301 h £0.1030 = £0.003774/h each | £0.18 | £5.51 (2 × £2.76) |

## Every billed line of the estate

| Line | Resource | Window cost | £/day | £/month | Basis |
|---|---|---|---|---|---|
| B2s nodes | aks-nodepool1-24453234-vmss | £3.3837 | £3.4199 | £104.03 | 94.983 node-h, £0.035624/node-h × 4 nodes × 730.08 h |
| PG compute `B2S` | jtoye-stg-pg | £1.4340 | £1.3767 | £41.88 | 25.000 h, £0.057361/h × 730.08 h |
| PG `Storage Data Stored` | jtoye-stg-pg | £0.1079 | £0.1036 | £3.15 | £0.1079 over 25.000 h × 730.08 h |
| Static Public IP | jtoye-stg-ingress-ip | £0.0993 | £0.0906 | £2.76 | 26.301 h, £0.003774/h × 730.08 h |
| Static Public IP | jtoye-stg-outbound-ip | £0.1030 | £0.0906 | £2.76 | 27.301 h, £0.003774/h × 730.08 h |
| `E3 LRS Disk` (PVC) | pvc-5a186563 | £0.0245 | £0.0223 | £0.68 | over 26.301 h × 730.08 h |
| `E3 LRS Disk` (PVC) | pvc-96a77647 | £0.0161 | £0.0147 | £0.45 | over 26.301 h × 730.08 h |
| Key Vault `Operations` | jtoye-stg-kv | £0.0056 | £0.0051 | £0.16 | over 26.301 h × 730.08 h |
| `E4 LRS Disk Operations` | pvc-96a77647 | £0.0038 | £0.0035 | £0.11 | over 26.301 h × 730.08 h |
| `E4 LRS Disk Operations` | pvc-5a186563 | £0.0009 | £0.0008 | £0.03 | over 26.301 h × 730.08 h |
| `Intra Continent Data Transfer Out` | aks-nodepool1-24453234-vmss | £0.0003 | £0.0003 | £0.01 | over 26.301 h × 730.08 h |
| Blob (jtoyestgmedia, jtoyestgbackup), LB data processed, other `- Free` meters | 16 lines | £0.0000 | £0.00 | £0.00 | every line £0.0000 in the window |
| **Estate total** | | | **£5.13** | **£156.00** | |

## Against the ceiling

| | £/month | vs D-03 £150 | vs D-03 raised by D-34 (£150 + ~£26 = ~£176) |
|---|---|---|---|
| Research projection, 3 nodes, LB free (29-RESEARCH) | £131.22 | −£18.78 | |
| D-34 expectation, 4 nodes (29-CONTEXT D-34a) | about £157–171 | | |
| **Measured, 4 nodes, today's meters** | **£156.00** | **+£6.00 over** | **about £20 under** |
| If the LB leaves its free meter (research +£13.80) | £169.80 | +£19.80 | about £6 under |
| If FreeTierInfrastructureCost starts billing (research +£27.52) | £183.52 | +£33.52 | about £8 over |
| Both | £197.32 | +£47.32 | about £21 over |

The measured figure sits at the bottom of the range the owner accepted on 2026-10-19 (D-34a), because neither
unresolved meter bills today. It is £6.00 over the original D-03 ceiling, all of it the fourth node (£26.01/month).

## snackpass-pg, shown separately (another project's server, not this estate)

| Line | Resource | Window cost | £/day | £/month | State |
|---|---|---|---|---|---|
| `Storage Data Stored` | snackpass-pg | £0.1166 | £0.1119 | £3.40 | already billing on the paid storage meter |

Research assumed its storage stayed free until 2026-11-08; the usage data shows it billing now, at £3.40/month
(research floor £3.21). No compute line appears in the window, so it did not auto-start. After 2026-11-08 the
compute free allowance also ends: each auto-start then bills compute on top (research: +£0.34/day while started).
It is outside the estate total above and outside this phase's ceiling; its disposition belongs to its own project.

## Owner decision

**Pending.** The owner answers option-hold, option-raise (with the number) or option-lever (naming the lever).
This section records the answer and its date before the phase closes.
