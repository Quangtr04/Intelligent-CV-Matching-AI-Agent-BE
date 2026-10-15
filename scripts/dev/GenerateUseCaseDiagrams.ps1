[CmdletBinding()]
param(
    [string]$ModelPath,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Drawing

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $ModelPath) {
    $ModelPath = Join-Path $repositoryRoot 'docs/diagrams/edunexus-usecase-model.json'
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repositoryRoot 'docs/diagrams'
}
$ModelPath = [System.IO.Path]::GetFullPath($ModelPath)
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$allowedRoot = $repositoryRoot.TrimEnd('\') + '\'
if (-not ($OutputDirectory + '\').StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputDirectory must be inside the repository.'
}
if (-not (Test-Path -LiteralPath $OutputDirectory)) {
    New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
}
$model = Get-Content -LiteralPath $ModelPath -Raw -Encoding UTF8 | ConvertFrom-Json
$utf8 = New-Object System.Text.UTF8Encoding($false)
$script:measurementBitmap = New-Object System.Drawing.Bitmap(1, 1)
$script:measurementGraphics = [System.Drawing.Graphics]::FromImage($script:measurementBitmap)
$script:measurementGraphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$script:fontCache = @{}

function Get-Field($Object, [string]$Name, $Default = $null) {
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return $Default }
    return $property.Value
}

function Escape-Xml([string]$Value) {
    return [System.Security.SecurityElement]::Escape($Value)
}

function Number([double]$Value) {
    return $Value.ToString('0.###', [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-Font([double]$Size, [bool]$Bold = $false) {
    $key = "$Size/$Bold"
    if (-not $script:fontCache.ContainsKey($key)) {
        $style = [System.Drawing.FontStyle]::Regular
        if ($Bold) { $style = [System.Drawing.FontStyle]::Bold }
        $script:fontCache[$key] = New-Object System.Drawing.Font('Segoe UI', [single]$Size, $style, [System.Drawing.GraphicsUnit]::Pixel)
    }
    return $script:fontCache[$key]
}

function Get-TextWidth([string]$Text, [double]$Size, [bool]$Bold = $false) {
    return $script:measurementGraphics.MeasureString($Text, (Get-Font $Size $Bold), 10000, [System.Drawing.StringFormat]::GenericTypographic).Width
}

function Wrap-Text([string]$Text, [double]$Width, [double]$Size, [bool]$Bold = $false) {
    $result = New-Object 'System.Collections.Generic.List[string]'
    $Text = $Text.Replace('\n', "`n")
    foreach ($paragraph in ($Text -split "`r?`n")) {
        if ($paragraph.Length -eq 0) { $result.Add(''); continue }
        $line = ''
        foreach ($word in ($paragraph -split '\s+')) {
            $candidate = ($line + ' ' + $word).Trim()
            if ($line -and (Get-TextWidth $candidate $Size $Bold) -gt $Width) {
                $result.Add($line)
                $line = $word
            } else { $line = $candidate }
        }
        $result.Add($line)
    }
    return ,$result.ToArray()
}

function Add-Text($Scene, [string]$Text, [double]$X, [double]$Y, [double]$Width, [double]$Height,
    [double]$Size = 18, [string]$Color = '#263449', [bool]$Bold = $false,
    [string]$Align = 'center', [string]$Vertical = 'middle') {
    $lines = Wrap-Text $Text $Width $Size $Bold
    $lineHeight = $Size * 1.3
    $top = $Y
    if ($Vertical -eq 'middle') { $top += ($Height - $lines.Count * $lineHeight) / 2 }
    foreach ($line in $lines) {
        $Scene.Add(@{type='text';text=$line;x=$X;y=$top;width=$Width;height=$lineHeight;size=$Size;color=$Color;bold=$Bold;align=$Align})
        $top += $lineHeight
    }
}

function Add-Shape($Scene, [string]$Type, [double]$X, [double]$Y, [double]$Width, [double]$Height,
    [string]$Fill = '#ffffff', [string]$Stroke = '#52647b', [double]$StrokeWidth = 1.5,
    [bool]$Dashed = $false, [double]$Radius = 0) {
    $Scene.Add(@{type=$Type;x=$X;y=$Y;width=$Width;height=$Height;fill=$Fill;stroke=$Stroke;strokeWidth=$StrokeWidth;dashed=$Dashed;radius=$Radius})
}

function Add-Line($Scene, $Points, [string]$Type = 'association', [string]$Color = '#52647b') {
    $Scene.Add(@{type='line';points=$Points;stroke=$Color;strokeWidth=1.6;dashed=($Type -in @('include','extend'));edgeType=$Type})
}

function Get-EdgeLabel($Edge) {
    $specified = Get-Field $Edge 'label' ''
    $specified = $specified.Replace('<<include>>', ([string][char]0x00ab + 'include' + [char]0x00bb))
    $specified = $specified.Replace('<<extend>>', ([string][char]0x00ab + 'extend' + [char]0x00bb))
    if ($specified) { return $specified }
    if ($Edge.type -in @('include','extend')) { return ([string][char]0x00ab + $Edge.type + [char]0x00bb) }
    return ''
}

function Normalize-ActorRoutes($Page) {
    $actors = @{}
    foreach ($actor in @($Page.actors)) { $actors[$actor.id] = $actor }
    foreach ($edge in @(Get-Field $Page 'edges' @())) {
        $points = New-Object 'System.Collections.Generic.List[object]'
        foreach ($point in $edge.points) { $points.Add(@{x=[double]$point.x;y=[double]$point.y}) }
        if ($actors.ContainsKey($edge.source)) {
            $actor = $actors[$edge.source]
            $cx = $actor.x + $actor.width / 2
            $first = $points[0]
            if ($edge.type -eq 'generalization' -and $first.y -ge ($actor.y + $actor.height - 5)) {
                $side = -1
                if ($cx -gt $Page.width / 2) { $side = 1 }
                $outside = $actor.x - 18
                if ($side -gt 0) { $outside = $actor.x + $actor.width + 18 }
                $prefix = @(
                    @{x=($cx + $side * 24);y=($actor.y + 78)},
                    @{x=$outside;y=($actor.y + 78)},
                    @{x=$outside;y=$first.y}
                )
                $points.InsertRange(0, [object[]]$prefix)
            } elseif (-not ([Math]::Abs($first.x - $cx) -lt 2 -and $first.y -le ($actor.y + 11))) {
                $side = -1
                if ($points[1].x -gt $cx) { $side = 1 }
                $points[0] = @{x=($cx + $side * 25);y=($actor.y + 34)}
            }
        }
        if ($actors.ContainsKey($edge.target)) {
            $actor = $actors[$edge.target]
            $cx = $actor.x + $actor.width / 2
            $last = $points[$points.Count - 1]
            $side = -1
            if ($points[$points.Count - 2].x -gt $cx) { $side = 1 }
            $contact = @{x=($cx + $side * 25);y=($actor.y + 34)}
            if ($edge.type -eq 'generalization') {
                $approachX = $contact.x + $side * 30
                $points.RemoveAt($points.Count - 1)
                $points.Add(@{x=$approachX;y=$last.y})
                $points.Add(@{x=$approachX;y=$contact.y})
                $points.Add($contact)
            } else {
                $points[$points.Count - 1] = $contact
            }
        }
        $edge.points = $points.ToArray()
    }
}

function New-Scene($Page) {
    $scene = New-Object 'System.Collections.Generic.List[object]'
    Add-Shape $scene 'rect' 0 0 $Page.width $Page.height '#ffffff' 'none' 0
    Add-Text $scene $Page.title 40 22 ($Page.width - 80) 45 28 '#17324e' $true
    $subtitle = Get-Field $Page 'subtitle' ''
    if ($subtitle) { Add-Text $scene $subtitle 40 70 ($Page.width - 80) 42 17 '#586a7f' }
    $boundary = $Page.boundary
    Add-Shape $scene 'rect' $boundary.x $boundary.y $boundary.width $boundary.height '#ffffff' '#263b52' 2
    Add-Text $scene $boundary.label ($boundary.x + 20) ($boundary.y + 12) ($boundary.width - 40) 40 23 '#17324e' $true 'left'
    foreach ($group in @(Get-Field $Page 'groups' @())) {
        $fill = Get-Field $group 'color' '#f5f8fc'
        Add-Shape $scene 'rect' $group.x $group.y $group.width $group.height $fill '#d5dfeb' 1 $false 8
        Add-Text $scene $group.label ($group.x + 16) ($group.y + 9) ($group.width - 32) 34 18 '#455e7a' $true 'left'
    }
    foreach ($edge in @(Get-Field $Page 'edges' @())) { Add-Line $scene $edge.points $edge.type (Get-Field $edge 'color' '#52647b') }
    foreach ($useCase in @($Page.useCases)) {
        Add-Shape $scene 'ellipse' $useCase.x $useCase.y $useCase.width $useCase.height (Get-Field $useCase 'color' '#ffffff') '#3d5875' 1.7
        Add-Text $scene $useCase.label ($useCase.x + 24) ($useCase.y + 8) ($useCase.width - 48) ($useCase.height - 16) (Get-Field $useCase 'fontSize' 18)
    }
    foreach ($actor in @($Page.actors)) {
        $cx = $actor.x + $actor.width / 2
        $color = Get-Field $actor 'color' '#29465f'
        Add-Shape $scene 'ellipse' ($cx - 11) $actor.y 22 22 '#ffffff' $color 1.9
        Add-Line $scene @(@{x=$cx;y=($actor.y+22)},@{x=$cx;y=($actor.y+53)}) 'association' $color
        Add-Line $scene @(@{x=($cx-25);y=($actor.y+34)},@{x=($cx+25);y=($actor.y+34)}) 'association' $color
        Add-Line $scene @(@{x=($cx-24);y=($actor.y+78)},@{x=$cx;y=($actor.y+53)},@{x=($cx+24);y=($actor.y+78)}) 'association' $color
        Add-Text $scene $actor.label $actor.x ($actor.y + 84) $actor.width ($actor.height - 84) (Get-Field $actor 'fontSize' 19) $color $true 'center' 'top'
    }
    foreach ($edge in @(Get-Field $Page 'edges' @())) {
        $label = Get-EdgeLabel $edge
        if (-not $label) { continue }
        $middle = $edge.points[[int][Math]::Floor(($edge.points.Count - 1) / 2)]
        $lx = Get-Field $edge 'labelX' $middle.x
        $ly = Get-Field $edge 'labelY' ($middle.y - 12)
        $labelLines = $label.Replace('\n', "`n") -split "`r?`n"
        $labelWidth = 12 + ($labelLines | ForEach-Object { Get-TextWidth $_ 16 } | Measure-Object -Maximum).Maximum
        $labelHeight = 8 + $labelLines.Count * 20.8
        Add-Shape $scene 'rect' ($lx - $labelWidth / 2) ($ly - $labelHeight / 2) $labelWidth $labelHeight '#ffffff' 'none' 0
        Add-Text $scene $label ($lx - $labelWidth / 2) ($ly - $labelHeight / 2) $labelWidth $labelHeight 16 '#40526b'
    }
    foreach ($note in @(Get-Field $Page 'notes' @())) {
        Add-Shape $scene 'rect' $note.x $note.y $note.width $note.height (Get-Field $note 'color' '#f4f7fb') '#d5dfeb' 1 $false 6
        Add-Text $scene $note.text ($note.x + 16) ($note.y + 12) ($note.width - 32) ($note.height - 24) (Get-Field $note 'fontSize' 16) '#52647b' $false 'left'
    }
    return ,$scene
}

function Get-Arrow($Item) {
    $end = $Item.points[$Item.points.Count - 1]
    $before = $Item.points[$Item.points.Count - 2]
    $dx = $end.x - $before.x
    $dy = $end.y - $before.y
    $distance = [Math]::Sqrt($dx * $dx + $dy * $dy)
    if ($distance -lt 0.01) { throw 'Arrow cannot have an empty final segment.' }
    $ux = $dx / $distance
    $uy = $dy / $distance
    $length = 12
    $halfWidth = 5.5
    if ($Item.edgeType -eq 'generalization') { $length = 16; $halfWidth = 8 }
    return @(
        @{x=($end.x - $length*$ux + $halfWidth*$uy);y=($end.y - $length*$uy - $halfWidth*$ux)},
        @{x=$end.x;y=$end.y},
        @{x=($end.x - $length*$ux - $halfWidth*$uy);y=($end.y - $length*$uy + $halfWidth*$ux)}
    )
}

function Write-Svg($Page, $Scene, [string]$Path) {
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine('<?xml version="1.0" encoding="UTF-8"?>')
    [void]$builder.AppendLine('<svg xmlns="http://www.w3.org/2000/svg" width="' + $Page.width + '" height="' + $Page.height + '" viewBox="0 0 ' + $Page.width + ' ' + $Page.height + '" role="img" aria-labelledby="title description">')
    [void]$builder.AppendLine('<title id="title">' + (Escape-Xml $Page.title) + '</title>')
    [void]$builder.AppendLine('<desc id="description">' + (Escape-Xml (Get-Field $Page 'subtitle' $Page.title)) + '</desc>')
    foreach ($item in $Scene) {
        switch ($item.type) {
            'text' {
                $tx = $item.x
                $anchor = 'start'
                if ($item.align -eq 'center') { $tx += $item.width / 2; $anchor = 'middle' }
                if ($item.align -eq 'right') { $tx += $item.width; $anchor = 'end' }
                $weight = '400'
                if ($item.bold) { $weight = '600' }
                $line = '<text x="' + (Number $tx) + '" y="' + (Number ($item.y + $item.size)) + '" text-anchor="' + $anchor + '" font-family="Segoe UI,Arial,sans-serif" font-size="' + (Number $item.size) + '" font-weight="' + $weight + '" fill="' + $item.color + '">' + (Escape-Xml $item.text) + '</text>'
            }
            'line' {
                $points = ($item.points | ForEach-Object { (Number $_.x) + ',' + (Number $_.y) }) -join ' '
                $dash = ''
                if ($item.dashed) { $dash = ' stroke-dasharray="7 5"' }
                $line = '<polyline points="' + $points + '" fill="none" stroke="' + $item.stroke + '" stroke-width="' + (Number $item.strokeWidth) + '" stroke-linejoin="round" stroke-linecap="round"' + $dash + '/>'
                if ($item.edgeType -in @('include','extend','generalization')) {
                    $arrow = ((Get-Arrow $item) | ForEach-Object { (Number $_.x) + ',' + (Number $_.y) }) -join ' '
                    $tag = 'polyline'
                    $fill = 'none'
                    if ($item.edgeType -eq 'generalization') { $tag = 'polygon'; $fill = '#ffffff' }
                    $line += '<' + $tag + ' points="' + $arrow + '" fill="' + $fill + '" stroke="' + $item.stroke + '" stroke-width="' + (Number $item.strokeWidth) + '"/>'
                }
            }
            'ellipse' {
                $line = '<ellipse cx="' + (Number ($item.x + $item.width/2)) + '" cy="' + (Number ($item.y + $item.height/2)) + '" rx="' + (Number ($item.width/2)) + '" ry="' + (Number ($item.height/2)) + '" fill="' + $item.fill + '" stroke="' + $item.stroke + '" stroke-width="' + (Number $item.strokeWidth) + '"/>'
            }
            'rect' {
                $line = '<rect x="' + (Number $item.x) + '" y="' + (Number $item.y) + '" width="' + (Number $item.width) + '" height="' + (Number $item.height) + '" rx="' + (Number $item.radius) + '" fill="' + $item.fill + '" stroke="' + $item.stroke + '" stroke-width="' + (Number $item.strokeWidth) + '"/>'
            }
        }
        [void]$builder.AppendLine($line)
    }
    [void]$builder.AppendLine('</svg>')
    [System.IO.File]::WriteAllText($Path, $builder.ToString(), $utf8)
}

function Get-Color([string]$Value) {
    return [System.Drawing.ColorTranslator]::FromHtml($Value)
}

function Write-Png($Page, $Scene, [string]$Path) {
    $bitmap = New-Object System.Drawing.Bitmap([int]($Page.width*2), [int]($Page.height*2), [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::White)
        $graphics.ScaleTransform(2, 2)
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
        foreach ($item in $Scene) {
            if ($item.type -eq 'text') {
                $brush = New-Object System.Drawing.SolidBrush((Get-Color $item.color))
                $format = [System.Drawing.StringFormat]::GenericTypographic.Clone()
                try {
                    $format.FormatFlags = $format.FormatFlags -bor [System.Drawing.StringFormatFlags]::NoWrap
                    if ($item.align -eq 'center') { $format.Alignment = [System.Drawing.StringAlignment]::Center }
                    if ($item.align -eq 'right') { $format.Alignment = [System.Drawing.StringAlignment]::Far }
                    $rectangle = New-Object System.Drawing.RectangleF([single]$item.x,[single]$item.y,[single]$item.width,[single]$item.height)
                    $graphics.DrawString($item.text, (Get-Font $item.size $item.bold), $brush, $rectangle, $format)
                } finally { $brush.Dispose(); $format.Dispose() }
                continue
            }
            $stroke = $item.stroke
            if ($stroke -eq 'none') { $stroke = '#ffffff' }
            $pen = New-Object System.Drawing.Pen((Get-Color $stroke), [single][Math]::Max(0.1,$item.strokeWidth))
            $brush = $null
            try {
                $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
                if ($item.dashed) { $pen.DashPattern = [single[]]@(4.375,3.125) }
                if ($item.type -eq 'line') {
                    $points = [System.Drawing.PointF[]]@($item.points | ForEach-Object { New-Object System.Drawing.PointF([single]$_.x,[single]$_.y) })
                    $graphics.DrawLines($pen,$points)
                    if ($item.edgeType -in @('include','extend','generalization')) {
                        $pen.DashStyle = [System.Drawing.Drawing2D.DashStyle]::Solid
                        $arrow = [System.Drawing.PointF[]]@((Get-Arrow $item) | ForEach-Object { New-Object System.Drawing.PointF([single]$_.x,[single]$_.y) })
                        if ($item.edgeType -eq 'generalization') {
                            $graphics.FillPolygon([System.Drawing.Brushes]::White,$arrow)
                            $graphics.DrawPolygon($pen,$arrow)
                        } else { $graphics.DrawLines($pen,$arrow) }
                    }
                    continue
                }
                $brush = New-Object System.Drawing.SolidBrush((Get-Color $item.fill))
                $x=[single]$item.x; $y=[single]$item.y; $w=[single]$item.width; $h=[single]$item.height
                if ($item.type -eq 'ellipse') {
                    $graphics.FillEllipse($brush,$x,$y,$w,$h)
                    if ($item.stroke -ne 'none') { $graphics.DrawEllipse($pen,$x,$y,$w,$h) }
                } elseif ($item.radius -gt 0) {
                    $roundedPath = New-Object System.Drawing.Drawing2D.GraphicsPath
                    try {
                        $r=[single]($item.radius*2)
                        $roundedPath.AddArc($x,$y,$r,$r,180,90)
                        $roundedPath.AddArc(($x+$w-$r),$y,$r,$r,270,90)
                        $roundedPath.AddArc(($x+$w-$r),($y+$h-$r),$r,$r,0,90)
                        $roundedPath.AddArc($x,($y+$h-$r),$r,$r,90,90)
                        $roundedPath.CloseFigure()
                        $graphics.FillPath($brush,$roundedPath)
                        if ($item.stroke -ne 'none') { $graphics.DrawPath($pen,$roundedPath) }
                    } finally { $roundedPath.Dispose() }
                } else {
                    $graphics.FillRectangle($brush,$x,$y,$w,$h)
                    if ($item.stroke -ne 'none') { $graphics.DrawRectangle($pen,$x,$y,$w,$h) }
                }
            } finally { $pen.Dispose(); if ($null -ne $brush) { $brush.Dispose() } }
        }
        $bitmap.Save($Path,[System.Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}

function Add-XmlElement($Document, $Parent, [string]$Name, [hashtable]$Attributes) {
    $element = $Document.CreateElement($Name)
    foreach ($key in $Attributes.Keys) { $element.SetAttribute($key, [string]$Attributes[$key]) }
    [void]$Parent.AppendChild($element)
    return $element
}

function Add-DrawioVertex($Document, $Root, [string]$Id, [string]$Label, [string]$Style,
    [double]$X, [double]$Y, [double]$Width, [double]$Height, [string]$ParentId = '1') {
    $cell = Add-XmlElement $Document $Root 'mxCell' @{id=$Id;value=$Label.Replace('\n',"`n");style=$Style;vertex='1';parent=$ParentId}
    [void](Add-XmlElement $Document $cell 'mxGeometry' @{x=(Number $X);y=(Number $Y);width=(Number $Width);height=(Number $Height);as='geometry'})
    return $cell
}

function Add-DrawioActor($Document, $Root, $Actor, [string]$TextStyle) {
    $color = Get-Field $Actor 'color' '#29465f'
    $size = Get-Field $Actor 'fontSize' 19
    $cx = $Actor.width / 2
    [void](Add-DrawioVertex $Document $Root $Actor.id '' 'group;' $Actor.x $Actor.y $Actor.width $Actor.height)
    [void](Add-DrawioVertex $Document $Root ($Actor.id+'-head') '' "ellipse;html=0;fillColor=#ffffff;strokeColor=$color;strokeWidth=1.9;" ($cx-11) 0 22 22 $Actor.id)
    $segments = @(
        @{name='body';points=@(@{x=$cx;y=22},@{x=$cx;y=53})},
        @{name='arms';points=@(@{x=($cx-25);y=34},@{x=($cx+25);y=34})},
        @{name='left-leg';points=@(@{x=$cx;y=53},@{x=($cx-24);y=78})},
        @{name='right-leg';points=@(@{x=$cx;y=53},@{x=($cx+24);y=78})}
    )
    foreach ($segment in $segments) {
        $cell=Add-XmlElement $Document $Root 'mxCell' @{id=($Actor.id+'-'+$segment.name);style="edgeStyle=none;rounded=0;html=0;strokeWidth=1.6;strokeColor=$color;startArrow=none;endArrow=none;";edge='1';parent=$Actor.id}
        $geometry=Add-XmlElement $Document $cell 'mxGeometry' @{relative='1';as='geometry'}
        [void](Add-XmlElement $Document $geometry 'mxPoint' @{x=(Number $segment.points[0].x);y=(Number $segment.points[0].y);as='sourcePoint'})
        [void](Add-XmlElement $Document $geometry 'mxPoint' @{x=(Number $segment.points[1].x);y=(Number $segment.points[1].y);as='targetPoint'})
    }
    [void](Add-DrawioVertex $Document $Root ($Actor.id+'-label') $Actor.label ($TextStyle+"verticalAlign=top;fontSize=$size;fontStyle=1;fontColor=$color;") 0 84 $Actor.width ($Actor.height-84) $Actor.id)
}

function Add-DrawioPage($Document, $MxFile, $Page) {
    $diagram = Add-XmlElement $Document $MxFile 'diagram' @{id=$Page.id;name=$Page.title}
    $graph = Add-XmlElement $Document $diagram 'mxGraphModel' @{dx=$Page.width;dy=$Page.height;grid='1';gridSize='10';guides='1';tooltips='1';connect='1';arrows='1';fold='1';page='1';pageScale='1';pageWidth=$Page.width;pageHeight=$Page.height;math='0';shadow='0'}
    $root = Add-XmlElement $Document $graph 'root' @{}
    [void](Add-XmlElement $Document $root 'mxCell' @{id='0'})
    [void](Add-XmlElement $Document $root 'mxCell' @{id='1';parent='0'})
    $textStyle = 'text;html=0;whiteSpace=wrap;rounded=0;align=center;verticalAlign=middle;fontFamily=Segoe UI;strokeColor=none;fillColor=none;'
    [void](Add-DrawioVertex $Document $root 'page-title' $Page.title ($textStyle+'fontSize=28;fontStyle=1;fontColor=#17324e;') 40 22 ($Page.width-80) 45)
    $subtitle = Get-Field $Page 'subtitle' ''
    if ($subtitle) { [void](Add-DrawioVertex $Document $root 'page-subtitle' $subtitle ($textStyle+'fontSize=17;fontColor=#586a7f;') 40 70 ($Page.width-80) 42) }
    $boundary=$Page.boundary
    [void](Add-DrawioVertex $Document $root 'system-boundary' '' 'rounded=0;whiteSpace=wrap;html=0;fillColor=#ffffff;strokeColor=#263b52;strokeWidth=2;' $boundary.x $boundary.y $boundary.width $boundary.height)
    [void](Add-DrawioVertex $Document $root 'system-boundary-label' $boundary.label ($textStyle+'align=left;fontSize=23;fontStyle=1;fontColor=#17324e;') ($boundary.x+20) ($boundary.y+12) ($boundary.width-40) 40)
    foreach ($group in @(Get-Field $Page 'groups' @())) {
        $fill=Get-Field $group 'color' '#f5f8fc'
        [void](Add-DrawioVertex $Document $root $group.id '' "rounded=1;arcSize=5;html=0;fillColor=$fill;strokeColor=#d5dfeb;strokeWidth=1;" $group.x $group.y $group.width $group.height)
        [void](Add-DrawioVertex $Document $root ($group.id+'-label') $group.label ($textStyle+'align=left;fontSize=18;fontStyle=1;fontColor=#455e7a;') ($group.x+16) ($group.y+9) ($group.width-32) 34)
    }
    $nodeMap=@{}
    foreach ($actor in @($Page.actors)) { $nodeMap[$actor.id]=$actor }
    foreach ($useCase in @($Page.useCases)) { $nodeMap[$useCase.id]=$useCase }
    $edgeNumber=0
    foreach ($edge in @(Get-Field $Page 'edges' @())) {
        $edgeNumber++
        $style='edgeStyle=none;rounded=0;html=0;strokeWidth=1.6;strokeColor='+(Get-Field $edge 'color' '#52647b')+';fontFamily=Segoe UI;fontSize=16;fontColor=#40526b;startArrow=none;endArrow=none;'
        if ($edge.type -in @('include','extend')) { $style+='dashed=1;dashPattern=7 5;endArrow=open;endFill=0;endSize=12;' }
        if ($edge.type -eq 'generalization') { $style+='endArrow=block;endFill=0;endSize=16;' }
        $attributes=@{id=('edge-'+$edgeNumber);value='';style=$style;edge='1';parent='1'}
        foreach ($side in @('source','target')) {
            $nodeId=Get-Field $edge $side ''
            if ($nodeId -and $nodeMap.ContainsKey($nodeId)) {
                $node=$nodeMap[$nodeId]
                $point=$edge.points[0]
                $prefix='exit'
                if ($side -eq 'target') { $point=$edge.points[$edge.points.Count-1]; $prefix='entry' }
                $boundsX=$node.x; $boundsY=$node.y; $boundsW=$node.width; $boundsH=$node.height
                $style+=$prefix+'X='+(Number (($point.x-$boundsX)/$boundsW))+';'+$prefix+'Y='+(Number (($point.y-$boundsY)/$boundsH))+';'+$prefix+'Perimeter=0;'
                $attributes[$side]=$nodeId
            }
        }
        $attributes.style=$style
        $cell=Add-XmlElement $Document $root 'mxCell' $attributes
        $geometry=Add-XmlElement $Document $cell 'mxGeometry' @{relative='1';as='geometry'}
        [void](Add-XmlElement $Document $geometry 'mxPoint' @{x=(Number $edge.points[0].x);y=(Number $edge.points[0].y);as='sourcePoint'})
        $last=$edge.points[$edge.points.Count-1]
        [void](Add-XmlElement $Document $geometry 'mxPoint' @{x=(Number $last.x);y=(Number $last.y);as='targetPoint'})
        if ($edge.points.Count -gt 2) {
            $array=Add-XmlElement $Document $geometry 'Array' @{as='points'}
            for ($i=1; $i -lt $edge.points.Count-1; $i++) {
                [void](Add-XmlElement $Document $array 'mxPoint' @{x=(Number $edge.points[$i].x);y=(Number $edge.points[$i].y)})
            }
        }
    }
    foreach ($useCase in @($Page.useCases)) {
        $fill=Get-Field $useCase 'color' '#ffffff'
        $size=Get-Field $useCase 'fontSize' 18
        [void](Add-DrawioVertex $Document $root $useCase.id $useCase.label "ellipse;whiteSpace=wrap;html=0;align=center;verticalAlign=middle;spacing=16;fontFamily=Segoe UI;fontSize=$size;fontColor=#263449;fillColor=$fill;strokeColor=#3d5875;strokeWidth=1.7;" $useCase.x $useCase.y $useCase.width $useCase.height)
    }
    foreach ($actor in @($Page.actors)) {
        Add-DrawioActor $Document $root $actor $textStyle
    }
    $edgeNumber=0
    foreach ($edge in @(Get-Field $Page 'edges' @())) {
        $edgeNumber++
        $label=Get-EdgeLabel $edge
        if (-not $label) { continue }
        $middle=$edge.points[[int][Math]::Floor(($edge.points.Count-1)/2)]
        $lx=Get-Field $edge 'labelX' $middle.x
        $ly=Get-Field $edge 'labelY' ($middle.y-12)
        $labelLines=$label.Replace('\n',"`n") -split "`r?`n"
        $labelWidth=12+($labelLines | ForEach-Object { Get-TextWidth $_ 16 } | Measure-Object -Maximum).Maximum
        $labelHeight=8+$labelLines.Count*20.8
        [void](Add-DrawioVertex $Document $root ('edge-label-'+$edgeNumber) $label ($textStyle+'fontSize=16;fontColor=#40526b;fillColor=#ffffff;') ($lx-$labelWidth/2) ($ly-$labelHeight/2) $labelWidth $labelHeight)
    }
    $noteNumber=0
    foreach ($note in @(Get-Field $Page 'notes' @())) {
        $noteNumber++
        $fill=Get-Field $note 'color' '#f4f7fb'
        $size=Get-Field $note 'fontSize' 16
        [void](Add-DrawioVertex $Document $root ('note-'+$noteNumber) $note.text "rounded=1;arcSize=5;whiteSpace=wrap;html=0;align=left;verticalAlign=middle;spacing=16;fontFamily=Segoe UI;fontSize=$size;fontColor=#52647b;fillColor=$fill;strokeColor=#d5dfeb;strokeWidth=1;" $note.x $note.y $note.width $note.height)
    }
}

function Test-Page($Page) {
    if ($Page.width -le 0 -or $Page.height -le 0) { throw 'Page dimensions must be positive.' }
    if ($Page.fileStem -notmatch '^edunexus-usecase(?:-[a-z0-9-]+)?$') { throw 'Unexpected diagram fileStem.' }
    $ids=@{}
    foreach ($node in @($Page.actors)+@($Page.useCases)+@(Get-Field $Page 'groups' @())) {
        if ($ids.ContainsKey($node.id)) { throw "Duplicate node id $($node.id)." }
        $ids[$node.id]=$true
        if ($node.x -lt 0 -or $node.y -lt 0 -or ($node.x+$node.width) -gt $Page.width -or ($node.y+$node.height) -gt $Page.height) {
            throw "Node $($node.id) is outside its page."
        }
    }
    foreach ($edge in @(Get-Field $Page 'edges' @())) {
        if ($edge.type -notin @('association','include','extend','generalization')) { throw "Invalid UML edge type $($edge.type)." }
        if ($edge.points.Count -lt 2) { throw 'An edge needs at least two route points.' }
        foreach ($side in @('source','target')) {
            $id=Get-Field $edge $side ''
            if ($id -and -not $ids.ContainsKey($id)) { throw "Unknown $side node $id." }
        }
    }
}

try {
    $document=New-Object System.Xml.XmlDocument
    [void]$document.AppendChild($document.CreateXmlDeclaration('1.0','UTF-8',$null))
    $mxFile=Add-XmlElement $document $document 'mxfile' @{host='app.diagrams.net';version='24.7.17';type='device'}
    foreach ($page in @($model.pages)) {
        Test-Page $page
        Normalize-ActorRoutes $page
        $scene=New-Scene $page
        $svgPath=Join-Path $OutputDirectory ($page.fileStem+'.svg')
        $pngPath=Join-Path $OutputDirectory ($page.fileStem+'.png')
        Write-Svg $page $scene $svgPath
        Write-Png $page $scene $pngPath
        Add-DrawioPage $document $mxFile $page
        Write-Output ("Generated {0}: SVG {1}x{2}, PNG {3}x{4}" -f $page.fileStem,$page.width,$page.height,($page.width*2),($page.height*2))
    }
    $settings=New-Object System.Xml.XmlWriterSettings
    $settings.Encoding=$utf8
    $settings.Indent=$true
    $writer=[System.Xml.XmlWriter]::Create((Join-Path $OutputDirectory 'edunexus-usecase.drawio'),$settings)
    try { $document.Save($writer) } finally { $writer.Dispose() }
    Write-Output ("Generated editable draw.io document with {0} pages." -f @($model.pages).Count)
} finally {
    foreach ($font in $script:fontCache.Values) {
        if ($font -is [System.IDisposable]) { $font.Dispose() }
    }
    $script:measurementGraphics.Dispose()
    $script:measurementBitmap.Dispose()
}
