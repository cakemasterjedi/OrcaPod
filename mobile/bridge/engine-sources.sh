#!/usr/bin/env bash
# The variables set here are read by the scripts that source this file.
# shellcheck disable=SC2034
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 OrcaPad contributors
# Copyright (c) 2026 OrcaPod contributors
# Adapted from OrcaPad (https://github.com/iSteeb/OrcaPad), Bridge/engine-sources.sh.
#
# Shared engine compile list — the single source of truth for every build of
# the headless engine (host tests, the CLI, and the Android NDK build). The
# builds must never drift apart: the duplicate archive-member bug OrcaPad hit
# came from lists diverging.
#
# Each record is one line:
#   cxx<TAB>/absolute/path.cpp<TAB>extra flags (space separated, optional)
#   c  <TAB>/absolute/path.c<TAB>extra flags (space separated, optional)
BRIDGE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$BRIDGE/../.." && pwd)"

# Pinned header-only / build-only dependency versions. Boost, CGAL and Eigen
# follow OrcaSlicer's own deps/ pins so the engine compiles against exactly
# the versions the desktop build uses.
BOOST_VER=1.84.0
CGAL_VER=6.2.1
EIGEN_VER=5.0.1
TBB_VER=2021.5.0
BOOST_DIR="$BRIDGE/cache/boost-$BOOST_VER"
BOOST_INC="$BRIDGE/cache/boost-include-$BOOST_VER"
CGAL_INC="$BRIDGE/cache/CGAL-$CGAL_VER/include"
EIGEN_INC="$BRIDGE/cache/eigen-$EIGEN_VER"
TBB_SRC="$BRIDGE/cache/oneTBB-$TBB_VER"
# OpenCASCADE for STEP import: OrcaSlicer's deps/OCCT pin and patch.
OCCT_VER=8.0.1
OCCT_SRC="$BRIDGE/cache/occt-$OCCT_VER"
# ORCAPOD_STEP=real (default) links OpenCASCADE and the real Format/STEP.cpp;
# stub keeps the loud STEP stubs and builds without OpenCASCADE.
ORCAPOD_STEP="${ORCAPOD_STEP:-real}"
case "$ORCAPOD_STEP" in
    real|stub) ;;
    *) echo "FATAL: ORCAPOD_STEP must be real or stub" >&2; exit 1 ;;
esac

engine_sources() {
    local SRC DEPS f
    SRC="$REPO/src"
    DEPS="$REPO/deps_src"

    # vendored admesh (mesh read/repair)
    for f in connect normals shared stl_io stlinit util; do
        printf 'cxx\t%s\n' "$DEPS/admesh/$f.cpp"
    done

    # vendored qhull — EXACT file lists from deps_src/qhull/CMakeLists.txt
    # (libqhull_SOURCES). Do not glob, do not "fix": the C/C++ hook split
    # (qh_fprintf vs qh_malloc vs qh_fprintf_rbox ownership) is deliberate.
    for f in global_r stat_r geom2_r poly2_r merge_r libqhull_r geom_r poly_r \
             qset_r mem_r random_r usermem_r io_r user_r rboxlib_r; do
        printf 'c\t%s\n' "$DEPS/qhull/src/libqhull_r/$f.c"
    done
    for f in Coordinates PointCoordinates Qhull QhullFacet QhullFacetList \
             QhullFacetSet QhullHyperplane QhullPoint QhullPointSet QhullPoints \
             QhullQh QhullRidge QhullSet QhullStat QhullVertex QhullVertexSet \
             RboxPoints RoadError RoadLogEvent; do
        printf 'cxx\t%s\n' "$DEPS/qhull/src/libqhullcpp/$f.cpp"
    done

    # libslic3r geometry subset (grows link-error-driven)
    for f in libslic3r.cpp Point.cpp Line.cpp MultiPoint.cpp Polygon.cpp Polyline.cpp \
             BoundingBox.cpp ExPolygon.cpp ClipperUtils.cpp ClipperZUtils.cpp \
             Circle.cpp ArcFitter.cpp Geometry.cpp Geometry/ConvexHull.cpp \
             Geometry/MedialAxis.cpp Geometry/Voronoi.cpp Geometry/VoronoiUtils.cpp \
             Geometry/VoronoiUtilsCgal.cpp Geometry/VoronoiOffset.cpp ShortestPath.cpp \
             Tesselate.cpp ExtrusionEntity.cpp ExtrusionEntityCollection.cpp Flow.cpp \
             LocalesUtils.cpp TriangleMesh.cpp TriangleMeshSlicer.cpp Format/STL.cpp; do
        case "$f" in
            LocalesUtils.cpp) printf 'cxx\t%s\t-include sstream\n' "$SRC/libslic3r/$f" ;;
            Geometry/VoronoiUtilsCgal.cpp) printf 'cxx\t%s\t-include boost/multiprecision/gmp.hpp\n' "$SRC/libslic3r/$f" ;;
            *) printf 'cxx\t%s\n' "$SRC/libslic3r/$f" ;;
        esac
    done

    # files needing force-includes (transitive-include quirks / CGAL gmp_int)
    printf 'cxx\t%s\t-include thread\n' "$SRC/libslic3r/Feature/FuzzySkin/FuzzySkin.cpp"
    printf 'cxx\t%s\n' "$SRC/libslic3r/MinimumSpanningTree.cpp"
    printf 'cxx\t%s\t-include boost/multiprecision/gmp.hpp\n' "$SRC/libslic3r/Triangulation.cpp"

    # --- full slicing pipeline (census-verified 2026-10-02) ---
    for f in Model.cpp PrintConfig.cpp PrintBase.cpp Print.cpp PrintApply.cpp \
             PrintObject.cpp PrintObjectSlice.cpp PrintRegion.cpp \
             Layer.cpp LayerRegion.cpp PerimeterGenerator.cpp \
             Surface.cpp SurfaceCollection.cpp Slicing.cpp SlicingAdaptive.cpp \
             ElephantFootCompensation.cpp Brim.cpp BridgeDetector.cpp EdgeGrid.cpp \
             MinAreaBoundingBox.cpp MutablePolygon.cpp VariableWidth.cpp \
             PlaceholderParser.cpp GCodeWriter.cpp GCodeReader.cpp Time.cpp Thread.cpp \
             Semver.cpp ObjectID.cpp Config.cpp CustomGCode.cpp GCode.cpp Format/OBJ.cpp Format/objparser.cpp Format/AMF.cpp \
             GCode/WipeTower2.cpp GCode/GCodeProcessor.cpp GCode/ThumbnailData.cpp \
             GCode/AdaptivePAInterpolator.cpp GCode/AdaptivePAProcessor.cpp \
             GCode/PchipInterpolatorHelper.cpp GCode/FanMover.cpp \
             GCode/SmallAreaInfillFlowCompensator.cpp GCode/PrintExtents.cpp \
             GCode/RetractWhenCrossingPerimeters.cpp GCode/AvoidCrossingPerimeters.cpp \
             GCode/ConflictChecker.cpp GCode/SeamPlacer.cpp GCode/TimelapsePosPicker.cpp \
             GCode/CoolingBuffer.cpp GCode/SpiralVase.cpp GCode/PressureEqualizer.cpp \
             GCode/ToolOrdering.cpp GCode/ToolOrderUtils.cpp GCode/WipeTower.cpp \
             Algorithm/LineSplit.cpp Algorithm/RegionExpansion.cpp \
             SLA/IndexedMesh.cpp SVG.cpp \
             FilamentGroupUtils.cpp ParameterUtils.cpp ShortEdgeCollapse.cpp \
             IntersectionPoints.cpp FlushVolPredictor.cpp Geometry/Circle.cpp TriangleSetSampling.cpp FaceDetector.cpp TriangleSelector.cpp BuildVolume.cpp Extruder.cpp PrincipalComponents2D.cpp MultiMaterialSegmentation.cpp FilamentGroup.cpp ContourZ.cpp Feature/Interlocking/InterlockingGenerator.cpp Feature/Interlocking/VoxelUtils.cpp calib.cpp MaterialType.cpp FlushVolCalc.cpp NormalUtils.cpp \
             Preset.cpp PresetBundle.cpp AppConfig.cpp \
             miniz_extension.cpp Format/3mf.cpp Format/bbs_3mf.cpp NSVGUtils.cpp \
             Emboss.cpp ExPolygonsIndex.cpp Orient.cpp CutUtils.cpp \
             Measure.cpp AABBMesh.cpp QuadricEdgeCollapse.cpp TriangleMeshDeal.cpp \
             BeltBrim.cpp BeltPurge.cpp BeltSliceStrategy.cpp BeltTransform.cpp \
             ConnectedBodies.cpp ExclusionVolumeGeometry.cpp FilamentMixer.cpp \
             IMEXHelpers.cpp IMEXZones.cpp InstanceLock.cpp MeshRepair.cpp \
             MultiNozzleUtils.cpp PresetCacheFormat.cpp PublishSettings.cpp \
             TextureDisplacement.cpp Zipper.cpp Support/BeltFloorContext.cpp \
             GCode/BeltKinematics.cpp GCode/ElegooGCodeProcessorHelper.cpp \
             GCode/ExclusionVolumePathCheck.cpp GCode/ExclusionVolumeTravelAvoidance.cpp \
             GCode/MachineFrameTransform.cpp GCode/MachineKinematics.cpp \
             GCode/OrderingStrategies.cpp GCode/PreciseSeam.cpp GCode/SeamContour.cpp \
             GCode/WipePathHelpers.cpp GCode/WipeTowerEstimate.cpp \
             GCode/BeltBackTransform.cpp BeltGCode.cpp ColorDecomposeRecipe.cpp \
             TextureBake/TextureBakeDebug.cpp TextureBake/TextureBakeDecimate.cpp \
             TextureBake/TextureBakeDisplace.cpp TextureBake/TextureBakeFlip.cpp \
             TextureBake/TextureBakeIndex.cpp TextureBake/TextureBakeMesh.cpp \
             TextureBake/TextureBakePipeline.cpp TextureBake/TextureBakeRegularize.cpp \
             TextureBake/TextureBakeRelocate.cpp TextureBake/TextureBakeRepair.cpp \
             TextureBake/TextureBakeSubdivide.cpp; do
        case "$f" in
            # host libstdc++ does not leak <memory> transitively into this
            # header (Darwin libc++ does) — same adapter pattern as LocalesUtils
            Format/objparser.cpp) printf 'cxx\t%s\t-include memory\n' "$SRC/libslic3r/$f" ;;
            *) printf 'cxx\t%s\n' "$SRC/libslic3r/$f" ;;
        esac
    done

    # infills
    for f in Fill.cpp FillBase.cpp FillConcentric.cpp FillConcentricInternal.cpp \
             FillCrossHatch.cpp FillGyroid.cpp FillHoneycomb.cpp Fill3DHoneycomb.cpp \
             FillAdaptive.cpp FillLightning.cpp FillLine.cpp FillPlanePath.cpp \
             FillRectilinear.cpp FillTpmsD.cpp FillTpmsFK.cpp \
             AdaptiveInfillField.cpp FillCornerSmoothing.cpp FillSpiralInset.cpp \
             Lightning/Generator.cpp Lightning/Layer.cpp Lightning/DistanceField.cpp \
             Lightning/TreeNode.cpp; do
        printf 'cxx\t%s\n' "$SRC/libslic3r/Fill/$f"
    done

    # supports
    for f in SupportCommon.cpp SupportMaterial.cpp SupportSpotsGenerator.cpp \
             TreeSupport.cpp TreeSupport3D.cpp TreeModelVolumes.cpp; do
        printf 'cxx\t%s\n' "$SRC/libslic3r/Support/$f"
    done

    # Arachne wall generator
    for f in WallToolPaths.cpp SkeletalTrapezoidation.cpp SkeletalTrapezoidationGraph.cpp \
             utils/ExtrusionLine.cpp utils/PolylineStitcher.cpp utils/SquareGrid.cpp \
             BeadingStrategy/BeadingStrategy.cpp BeadingStrategy/BeadingStrategyFactory.cpp \
             BeadingStrategy/DistributedBeadingStrategy.cpp BeadingStrategy/LimitedBeadingStrategy.cpp \
             BeadingStrategy/OuterWallInsetBeadingStrategy.cpp BeadingStrategy/RedistributeBeadingStrategy.cpp \
             BeadingStrategy/WideningBeadingStrategy.cpp; do
        printf 'cxx\t%s\n' "$SRC/libslic3r/Arachne/$f"
    done

    # vendored glu-libtess (triangulation backend for Tesselate.cpp)
    # File list mirrors upstream deps_src/glu-libtess/CMakeLists.txt EXACTLY:
    # priorityq-heap.c / priorityq-sort.c are NOT standalone TUs upstream —
    # compiling them duplicates __gl_pqHeap* strong C symbols.
    for f in dict geom memalloc mesh normal priorityq render sweep tess tessmono; do
        printf 'c\t%s\n' "$DEPS/glu-libtess/src/$f.c"
    done

    # vendored semver (version parsing)
    printf 'c\t%s\n' "$DEPS/semver/semver.c"

    # vendored Clipper2 — EXACT CLIPPER2_SRC of deps_src/clipper2/CMakeLists.txt
    # (clipper2_z.cpp re-includes the sources with USINGZ for the Z variant)
    for f in clipper.engine clipper.offset clipper.rectclip clipper.triangulation clipper2_z; do
        printf 'cxx\t%s\n' "$DEPS/clipper2/Clipper2Lib/src/$f.cpp"
    done

    # vendored expat (XML for AMF/3mf metadata)
    for f in xmlparse xmltok xmlrole; do
        printf 'c\t%s\n' "$DEPS/expat/$f.c"
    done

    # vendored miniz (zip I/O)
    printf 'c\t%s\n' "$DEPS/miniz/miniz.c"

    # libnoise (fuzzy skin) — full source set
    for f in "$BRIDGE"/cache/Orca-deps-libnoise/src/*.cpp \
             "$BRIDGE"/cache/Orca-deps-libnoise/src/model/*.cpp \
             "$BRIDGE"/cache/Orca-deps-libnoise/src/module/*.cpp; do
        [ -f "$f" ] && printf 'cxx\t%s\n' "$f"
    done

    # boost::filesystem compiled bits — real upstream source (tag matches the
    # installed headers). The preset system (Preset.cpp / PresetBundle.cpp)
    # pulls path iteration + directory ops that headers alone don't provide.
    # windows_file_codecvt is Windows-only.
    for f in path operations directory exception path_traits portability \
             unique_path codecvt_error_category utf8_codecvt_facet; do
        printf 'cxx\t%s\t-std=c++20\n' "$BOOST_DIR/libs/filesystem/src/$f.cpp"
    done

    # arrange: libslic3r Arrange/ModelArrange over vendored libnest2d with
    # NLopt 2.5.0 (OrcaSlicer's pin, deps/NLopt/NLopt.cmake). Defines are
    # libnest2d's PUBLIC compile definitions (deps_src/libnest2d/CMakeLists).
    local NEST="-DLIBNEST2D_THREADING_tbb -DLIBNEST2D_STATIC -DLIBNEST2D_OPTIMIZER_nlopt -DLIBNEST2D_GEOMETRIES_libslic3r -I$BRIDGE/cache/nlopt-gen -I$BRIDGE/cache/nlopt/src/api"
    printf 'cxx\t%s\t%s\n' "$SRC/libslic3r/Arrange.cpp" "$NEST"
    printf 'cxx\t%s\t%s\n' "$SRC/libslic3r/ModelArrange.cpp" "$NEST"
    printf 'cxx\t%s\t%s\n' "$DEPS/libnest2d/src/libnest2d.cpp" "$NEST"
    # NLopt C sources — EXACT NLOPT_SOURCES of its CMakeLists.txt (NLOPT_CXX
    # and NLOPT_CXX11 off, as in OrcaSlicer's deps build)
    local NL="$BRIDGE/cache/nlopt/src"
    local NLI="-I$BRIDGE/cache/nlopt-gen -I$NL/api -I$NL/util"
    for d in direct cdirect praxis luksan crs mlsl mma cobyla newuoa neldermead auglag bobyqa isres slsqp esch; do
        NLI="$NLI -I$NL/algs/$d"
    done
    for f in algs/direct/DIRect algs/direct/direct_wrap algs/direct/DIRserial algs/direct/DIRsubrout \
             algs/cdirect/cdirect algs/cdirect/hybrid algs/praxis/praxis \
             algs/luksan/plis algs/luksan/plip algs/luksan/pnet algs/luksan/mssubs algs/luksan/pssubs \
             algs/crs/crs algs/mlsl/mlsl algs/mma/mma algs/mma/ccsa_quadratic algs/cobyla/cobyla \
             algs/newuoa/newuoa algs/neldermead/nldrmd algs/neldermead/sbplx algs/auglag/auglag \
             algs/bobyqa/bobyqa algs/isres/isres algs/slsqp/slsqp algs/esch/esch \
             api/general api/options api/optimize api/deprecated api/f77api \
             util/mt19937ar util/sobolseq util/timer util/stop util/redblack util/qsort_r util/rescale; do
        printf 'c\t%s\t%s\n' "$NL/$f.c" "$NLI"
    done

    # vendored header-only nanosvg: its implementation unit lives in the
    # upstream GUI (BitmapCache.cpp), so the Bridge provides one
    printf 'cxx\t%s\n' "$BRIDGE/engine-cxx/nanosvg_impl.cpp"

    # boost::thread compiled bits (pthread API) — real upstream sources at
    # the installed headers' tag; Format/bbs_3mf.cpp's project auto-backup
    # manager owns a boost::thread. Flags mirror libs/thread/CMakeLists.txt.
    for f in pthread/thread pthread/once future; do
        printf 'cxx\t%s\t-DBOOST_THREAD_SOURCE -DBOOST_THREAD_BUILD_LIB -DBOOST_THREAD_STATIC_LINK -DBOOST_THREAD_NO_LIB\n' \
            "$BOOST_DIR/libs/thread/src/$f.cpp"
    done

    # mesh booleans (desktop's Mesh Boolean gizmo) over vendored MCUT, and
    # MeshBoolean.cpp's CGAL corefinement. File list and define mirror
    # deps_src/mcut/CMakeLists.txt (thread pool on, as on desktop).
    local MCUT="-I$DEPS/mcut/include -DMCUT_WITH_COMPUTE_HELPER_THREADPOOL=1"
    for f in mcut kernel hmesh math bvh frontend preproc; do
        printf 'cxx\t%s\t%s\n' "$DEPS/mcut/source/$f.cpp" "$MCUT"
    done
    printf 'c\t%s\t%s\n' "$DEPS/mcut/source/shewchuk.c" "$MCUT"
    printf 'cxx\t%s\n' "$SRC/libslic3r/MeshBoolean.cpp"
    printf 'cxx\t%s\n' "$SRC/libslic3r/TryCatchSignal.cpp"

    # adapter stubs (DRC/SVG — loud throws, upstream untouched)
    printf 'cxx\t%s\n' "$BRIDGE/engine-cxx/format_stubs.cpp"
    # import name cleanup (desktop's name dialog; header-only Boost.Locale UTF)
    printf 'cxx\t%s\n' "$SRC/libslic3r/Format/ImportNames.cpp"
    # STEP: the real reader over OpenCASCADE, or loud stubs without it
    if [ "$ORCAPOD_STEP" = real ]; then
        printf 'cxx\t%s\n' "$SRC/libslic3r/Format/STEP.cpp"
        # boost::chrono's clocks (STEP.cpp polls its reader thread with
        # boost::this_thread::sleep_for)
        printf 'cxx\t%s\t-DBOOST_CHRONO_STATIC_LINK\n' "$BOOST_DIR/libs/chrono/src/chrono.cpp"
    else
        printf 'cxx\t%s\n' "$BRIDGE/engine-cxx/step_stubs.cpp"
    fi
    # the utils.cpp functions the closure needs (utils.cpp itself needs Boost.Log)
    printf 'cxx\t%s\n' "$BRIDGE/engine-cxx/utils_subset.cpp"
    # real thumbnail parse/encode (PNG via miniz, QOI vendored; JPEG hits the
    # loud adapter below)
    printf 'cxx\t%s\n' "$SRC/libslic3r/GCode/Thumbnails.cpp"
    printf 'cxx\t%s\n' "$BRIDGE/engine-cxx/jpeg_stubs.cpp"
    printf 'c\t%s\n' "$DEPS/qoi/qoilib.c"

    # our C Bridge boundary
    # (boost_filesystem_shim.cpp is retired: its POSIX implementations were
    # fully superseded by the real boost-filesystem-1.90 sources above, and
    # keeping both defined the same symbols — dead weight on iOS and a
    # multiple-definition hazard whenever both members get linked.)
    # orca_bridge.cpp resolves the full config through vendored nlohmann/json.
    printf 'cxx\t%s\t-I%s\n' "$BRIDGE/engine-cxx/orca_bridge.cpp" \
        "$DEPS/nlohmann"
}

# Fetches ALL build-only third-party sources listed above (idempotent;
# single source of truth for every build). Pins:
#   Boost 1.84.0, CGAL 6.2.1, Eigen 5.0.1, oneTBB 2021.5.0 (OrcaSlicer's deps/ pins),
#   cereal v1.3.0, NLopt v2.5.0, libnoise 1.0 (SoftFever/Orca-deps-libnoise)
_fetch_git() { # _fetch_git <url> <tag> <dir> <sentinel>
    [ -f "$3/$4" ] && return 0
    echo "==> fetching $(basename "$3") ($2) into mobile/bridge/cache/" >&2
    rm -rf "$3"
    git clone -q --depth 1 -b "$2" "$1" "$3"
    [ -f "$3/$4" ] || { echo "FATAL: $(basename "$3") fetch failed" >&2; return 1; }
}
_fetch_archive() { # _fetch_archive <url> <dir> <sentinel>
    [ -f "$2/$3" ] && return 0
    echo "==> downloading $(basename "$1") into mobile/bridge/cache/" >&2
    local tmp="$BRIDGE/cache/dl.$$"
    rm -rf "$tmp" "$2"; mkdir -p "$tmp"
    curl -fsSL "$1" -o "$tmp/archive"
    case "$1" in
        *.zip) (cd "$tmp" && unzip -q archive) ;;
        *) tar -xzf "$tmp/archive" -C "$tmp" ;;
    esac
    rm "$tmp/archive"
    mv "$tmp"/* "$2"; rm -rf "$tmp"
    [ -f "$2/$3" ] || { echo "FATAL: $(basename "$2") download failed" >&2; return 1; }
}
engine_fetch_deps() {
    mkdir -p "$BRIDGE/cache"
    _fetch_archive "https://github.com/boostorg/boost/releases/download/boost-$BOOST_VER/boost-$BOOST_VER.tar.gz" \
        "$BOOST_DIR" libs/filesystem/src/path.cpp || return 1
    # The GitHub release keeps each library's headers in libs/<lib>/include;
    # merge them into one include root as a staged Boost install would.
    if [ ! -f "$BOOST_INC/boost/version.hpp" ]; then
        echo "==> merging Boost headers" >&2
        rm -rf "$BOOST_INC.tmp"; mkdir -p "$BOOST_INC.tmp"
        local d
        for d in "$BOOST_DIR"/libs/*/include "$BOOST_DIR"/libs/numeric/*/include; do
            [ -d "$d/boost" ] && cp -r "$d/boost" "$BOOST_INC.tmp/"
        done
        rm -rf "$BOOST_INC"; mv "$BOOST_INC.tmp" "$BOOST_INC"
    fi
    _fetch_archive "https://github.com/CGAL/cgal/releases/download/v$CGAL_VER/CGAL-$CGAL_VER.zip" \
        "$BRIDGE/cache/CGAL-$CGAL_VER" include/CGAL/version.h || return 1
    _fetch_archive "https://gitlab.com/libeigen/eigen/-/archive/$EIGEN_VER/eigen-$EIGEN_VER.zip" \
        "$EIGEN_INC" Eigen/Core || return 1
    _fetch_git https://github.com/uxlfoundation/oneTBB "v$TBB_VER" "$TBB_SRC" include/oneapi/tbb.h || return 1
    _fetch_git https://github.com/USCiLab/cereal v1.3.0 "$BRIDGE/cache/cereal" include/cereal/access.hpp || return 1
    _fetch_git https://github.com/SoftFever/Orca-deps-libnoise 1.0 "$BRIDGE/cache/Orca-deps-libnoise" src/noisegen.cpp || return 1
    if [ "$ORCAPOD_STEP" = real ]; then
        _fetch_git https://github.com/Open-Cascade-SAS/OCCT "V${OCCT_VER//./_}" "$OCCT_SRC" CMakeLists.txt || return 1
        if [ ! -f "$OCCT_SRC/.orcapod-patched" ]; then
            git -C "$OCCT_SRC" apply --ignore-space-change --whitespace=fix \
                "$REPO/deps/OCCT/0001-BRepMesh-seam-pcurve-at-edge-parameter.patch" ||
                { echo "FATAL: OCCT patch failed" >&2; return 1; }
            touch "$OCCT_SRC/.orcapod-patched"
        fi
    fi
    _fetch_git https://github.com/stevengj/nlopt v2.5.0 "$BRIDGE/cache/nlopt" src/api/nlopt-in.hpp || return 1
    # configure-time headers (NLopt's CMake would generate these)
    mkdir -p "$BRIDGE/cache/nlopt-gen"
    if [ ! -f "$BRIDGE/cache/nlopt-gen/nlopt.hpp" ] ||
       [ "$BRIDGE/tools/gen_nlopt.py" -nt "$BRIDGE/cache/nlopt-gen/nlopt.hpp" ]; then
        python3 "$BRIDGE/tools/gen_nlopt.py" "$BRIDGE/cache/nlopt" \
            "$BRIDGE/cache/nlopt-gen" || { echo "FATAL: NLopt header generation failed" >&2; return 1; }
    fi
}

# Builds oneTBB (tbb + tbbmalloc, static) with CMake into <build>/tbb-install,
# using OrcaSlicer's deps/TBB options. Extra arguments are passed to CMake
# (a toolchain file for cross builds). Prints the install prefix.
engine_build_tbb() { # engine_build_tbb <build dir> [cmake args...]
    local build="$1"; shift
    local prefix="$build/tbb-install"
    if [ ! -f "$prefix/include/oneapi/tbb.h" ]; then
        echo "==> building oneTBB $TBB_VER" >&2
        if ! { cmake -S "$TBB_SRC" -B "$build/tbb-build" -G Ninja \
                   -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$prefix" \
                   -DCMAKE_INSTALL_LIBDIR=lib \
                   -DTBB_BUILD_SHARED=OFF -DBUILD_SHARED_LIBS=OFF -DTBB_TEST=OFF -DTBB_STRICT=OFF \
                   -DTBB_DISABLE_HWLOC_AUTOMATIC_SEARCH=ON -DTBB_ENABLE_IPO=OFF \
                   -DCMAKE_POSITION_INDEPENDENT_CODE=ON "$@" >&2 &&
               cmake --build "$build/tbb-build" >&2 &&
               cmake --install "$build/tbb-build" >&2; }; then
            echo "FATAL: oneTBB build failed" >&2
            return 1
        fi
    fi
    printf '%s\n' "$prefix"
}

# The OpenCASCADE toolkits Format/STEP.cpp uses, in link order (dependents
# first). Every other toolkit they need is listed after them.
OCCT_TOOLKITS=(TKDESTEP TKXCAF TKVCAF TKV3d TKService TKCAF TKLCAF TKCDF
               TKHLR TKDE TKXSBase TKMesh TKShHealing TKBO TKPrim TKTopAlgo
               TKGeomAlgo TKBRep TKGeomBase TKG3d TKG2d TKMath TKernel)

# Builds the static OpenCASCADE toolkits STEP import needs into
# <build>/occt-install, with OrcaSlicer's deps/OCCT options, except that only
# the STEP toolkits (and what they depend on) are built rather than whole
# modules. Extra arguments are passed to CMake. Prints the install prefix.
engine_build_occt() { # engine_build_occt <build dir> [cmake args...]
    local build="$1"; shift
    local prefix="$build/occt-install"
    if [ ! -f "$prefix/lib/libTKDESTEP.a" ]; then
        echo "==> building OpenCASCADE $OCCT_VER (STEP toolkits; this takes a while)" >&2
        if ! { cmake -S "$OCCT_SRC" -B "$build/occt-build" -G Ninja \
                   -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=17 \
                   -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
                   -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" \
                   -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections" \
                   -DBUILD_LIBRARY_TYPE=Static -DINSTALL_DIR="$prefix" \
                   -DINSTALL_DIR_LAYOUT=Unix -DINSTALL_DIR_LIB=lib \
                   -DINSTALL_DIR_INCLUDE=include/occt -DINSTALL_DIR_CMAKE=lib/cmake/occt \
                   -DUSE_TK=OFF -DUSE_TBB=OFF -DUSE_FREETYPE=OFF -DUSE_FFMPEG=OFF \
                   -DUSE_VTK=OFF -DUSE_OPENGL=OFF -DUSE_GLES2=OFF -DUSE_XLIB=OFF \
                   -DBUILD_DOC_Overview=OFF -DBUILD_GTEST=OFF \
                   -DBUILD_MODULE_FoundationClasses=OFF -DBUILD_MODULE_ModelingData=OFF \
                   -DBUILD_MODULE_ModelingAlgorithms=OFF -DBUILD_MODULE_Visualization=OFF \
                   -DBUILD_MODULE_ApplicationFramework=OFF -DBUILD_MODULE_DataExchange=OFF \
                   -DBUILD_MODULE_Draw=OFF \
                   -DBUILD_ADDITIONAL_TOOLKITS="TKDESTEP TKXCAF TKMesh" "$@" >&2 &&
               cmake --build "$build/occt-build" -j"${ORCAPOD_JOBS:-6}" >&2 &&
               cmake --install "$build/occt-build" >&2; }; then
            echo "FATAL: OpenCASCADE build failed" >&2
            return 1
        fi
    fi
    printf '%s\n' "$prefix"
}
