window.$ = window.jQuery;

window.miru = {};

miru.resetButton = function ($button, value) {
    $button.val(value);
    $button.removeAttr('disabled');
};

miru.partitions = {
    rebuild: function (ele) {
        var days = $('#days').val();
        var hotDeploy = $('#hotDeploy').is(':checked');
        var chunkStores = $('#chunkStores').is(':checked');
        var labIndexes = $('#labIndexes').is(':checked');

        var $button = $(ele);
        $button.attr('disabled', 'disabled');
        var value = $button.val();
        $.ajax({
            type: "POST",
            url: "/miru/config/rebuild",
            data: {
                "days": days,
                "hotDeploy": hotDeploy,
                "chunkStores": chunkStores,
                "labIndexes": labIndexes
            },
            //contentType: "application/json",
            success: function () {
                $button.val('Success');
                setTimeout(function () {
                    miru.resetButton($button, value);
                }, 2000);
            },
            error: function () {
                $button.val('Failure');
                setTimeout(function () {
                    miru.resetButton($button, value);
                }, 2000);
            }
        });
    }
};

miru.errors = {
    rebuild: function (ele, tenantId, partitionId) {
        var $button = $(ele);
        $button.attr('disabled', 'disabled');
        var value = $button.val();
        $.ajax({
            type: "POST",
            url: "/miru/config/rebuild/prioritize/" + tenantId + "/" + partitionId,
            //contentType: "application/json",
            success: function () {
                $button.val('Success');
                setTimeout(function () {
                    miru.resetButton($button, value);
                }, 2000);
            },
            error: function () {
                $button.val('Failure');
                setTimeout(function () {
                    miru.resetButton($button, value);
                }, 2000);
            }
        });
    }
};

miru.lab = {
    waves: {},
    data: {},
    initChart: function (which) {
        var $canvas = $(which);
        var ctx = which.getContext("2d");
        var id = $canvas.data('labWaveId');
        if (!miru.lab.waves[id]) {
            var data = miru.lab.data[id];

            miru.lab.waves[id] = new Chart(ctx, {
                type: $canvas.data('labWaveType'),
                data: data,
                options: {
                    maintainAspectRatio: false,
                    responsive: true,
                    legend: {
                        display: false
                    },
                    tooltips: {
                        enabled: true,
                        mode: 'label',
                        backgroundColor: 'rgba(100,100,100,0.8)'
                    },
                    gridLines: {
                        display: true,
                        color: "rgba(128,128,128,1)"
                    },
                    scaleLabel: {
                        fontColor: "rgba(200,200,200,1)"
                    },
                    scales: {
                        yAxes: [{
                                position: "right",
                                ticks: {
                                    beginAtZero: true
                                }
                            }]
                    }
                }
            });
        }
        miru.lab.waves[id].update();

    },
    init: function () {

        $('.lab-wave').each(function (i) {
            miru.lab.initChart(this);
        });
    }
};

$(document).ready(function () {
    if ($('.lab-wave').length) {
        miru.lab.init();
    }

    if ($('.lab-scroll-wave').length) {

        $('.lab-scroll-wave').each(function (j, va) {
            $(va).on('scroll', function () {
                $('.lab-scroll-wave').each(function (j, vb) {
                    if ($(va) !== $(vb)) {
                        $(vb).scrollLeft($(va).scrollLeft());
                    }
                });
            });
        });
    }

    $(function () {
        var hack = {};
        $('[rel="popover"]').popover({
            container: 'body',
            html: true,
            content: function () {
                console.log($(this).attr('id'));
                var h = $($(this).data('popover-content')).removeClass('hide');
                hack[$(this).attr('id')] = h;
                return h;
            }
        }).click(function (e) {
            e.preventDefault();
        }).on('hidden.bs.popover', function () {
            var h = hack[$(this).attr('id')];
            h.detach();
            h.addClass('hide');
            $('body').append(h);
        });
    });
});
